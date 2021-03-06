package mstparser

import com.google.common.collect.MinMaxPriorityQueue

class DependencyDecoder2O(pipe: DependencyPipe) extends DependencyDecoder(pipe) {
  def decodeNonProjective(
    instance: DependencyInstance,
    fvs: Array[Array[Array[FeatureVector]]],
    probs: Array[Array[Array[Double]]],
    fvsTr: Array[Array[Array[FeatureVector]]],
    probsTr: Array[Array[Array[Double]]],
    fvsSi: Array[Array[Array[FeatureVector]]],
    probsSi: Array[Array[Array[Double]]],
    fvsNt: Array[Array[Array[Array[FeatureVector]]]],
    probsNt: Array[Array[Array[Array[Double]]]],
    k: Int
  ): Seq[(FeatureVector, (IndexedSeq[Int], IndexedSeq[Int]))] = {
    val orig = this.decodeProjective(instance.length, fvs, probs, fvsTr, probsTr, fvsSi, probsSi, fvsNt, probsNt, 1)
    val (parse, labels) = orig.head._2

    val (nParse, nLabels) = this.rearrange(probs, probsTr, probsSi, probsNt, parse, labels)

    instance.heads = nParse
    instance.deprels = nLabels.map(this.pipe.typeAlphabet.values(_))

    (this.pipe.createFeatureVector(instance), (nParse, nLabels)) +: orig.tail
  }

  private def getSibs(ch: Int, par: Seq[Int]): (Int, Int) = ((
    if (par(ch) > ch) ch + 1 until par(ch)
    else ch - 1 until par(ch) by -1
  ).find(par(ch) == par(_)).getOrElse(par(ch)), (
    if (par(ch) < ch) ch + 1 until par.length
    else ch - 1 to 0 by -1
  ).find(par(ch) == par(_)).getOrElse(ch))

  protected def rearrange(
    probs: Array[Array[Array[Double]]],
    probsTr: Array[Array[Array[Double]]],
    probsSi: Array[Array[Array[Double]]],
    probsNt: Array[Array[Array[Array[Double]]]],
    parse: IndexedSeq[Int],
    labels: IndexedSeq[Int]
  ) = {
    val len = parse.size
    val staticTypes = if (this.pipe.labeled) Some(this.getTypes(probsNt, len)) else None

    var isChild = this.calcChilds(parse)

    var max = Double.PositiveInfinity

    val nParse = parse.toBuffer
    val nLabels = labels.toBuffer

    while (max > 0.0) {
      max = Double.NegativeInfinity

      var wh = -1
      var nP = -1
      var nL = -1

      val sibs = IndexedSeq.tabulate(len, len) {
        case (0, _) => (0, 0)
        case (i, j) => this.getSibs(i, nParse.updated(i, j))
      }

      val pairs = nParse.zipWithIndex

      pairs.tail.foreach { case (p, i) =>
        // Calculate change of removing edge.
        var (a, b) = sibs(i)(p)
        var lDir = i < p

        val change0 =
          probs(if (lDir) i else p)(if (lDir) p else i)(if (lDir) 1 else 0) +
          probsTr(p)(a)(i) +
          probsSi(a)(i)(if (a == p) 0 else 1) +
          ( if (b != i)
            probsTr(p)(i)(b) +
            probsSi(i)(b)(1) -
            probsTr(p)(a)(b) -
            probsSi(a)(b)(if (a == p) 0 else 1)
            else 0.0
          ) +
          ( if (this.pipe.labeled)
            probsNt(i)(nLabels(i))(if (lDir) 1 else 0)(0) +
            probsNt(p)(nLabels(i))(if (lDir) 1 else 0)(1)
            else 0.0
          )

        pairs.filter {
          case (_, j) => j != i && j != p && !isChild(i)(j)
        }.foreach { case (_, j) =>
          val (a, b) = sibs(i)(j)
          val lDir = i < j

          val change1 =
            probs(if (lDir) i else j)(if (lDir) j else i)(if (lDir) 1 else 0) +
            probsTr(j)(a)(i) +
            probsSi(a)(i)(if (a == j) 0 else 1) +
            ( if (b != i)
              probsTr(j)(i)(b) +
              probsSi(i)(b)(1) -
              probsTr(j)(a)(b) -
              probsSi(a)(b)(if (a == j) 0 else 1)
              else 0.0
            ) +
            staticTypes.map(ts =>
              probsNt(i)(ts(j)(i))(if (lDir) 1 else 0)(0) +
              probsNt(j)(ts(j)(i))(if (lDir) 1 else 0)(1)
            ).getOrElse(0.0)

          if (max < change1 - change0) {
            max = change1 - change0
            wh = i
            nP = j
            nL = staticTypes.map(_(j)(i)).getOrElse(0)
          }
        }
      }

      if (max > 0.0) {
        nParse(wh) = nP
        nLabels(wh) = nL
        isChild = this.calcChilds(nParse)
      }
    }
    (nParse.toIndexedSeq, nLabels.toIndexedSeq)
  }

  def decodeProjective(
    len: Int,
    fvs: Array[Array[Array[FeatureVector]]],
    probs: Array[Array[Array[Double]]],
    fvsTr: Array[Array[Array[FeatureVector]]],
    probsTr: Array[Array[Array[Double]]],
    fvsSi: Array[Array[Array[FeatureVector]]],
    probsSi: Array[Array[Array[Double]]],
    fvsNt: Array[Array[Array[Array[FeatureVector]]]],
    probsNt: Array[Array[Array[Array[Double]]]],
    kBest: Int
  ): Seq[(FeatureVector, (IndexedSeq[Int], IndexedSeq[Int]))] = {
    val staticTypes = if (this.pipe.labeled) Some(this.getTypes(probsNt, len)) else None
    val pf = new KBestParseForest(len - 1, kBest, true)
    val qb = MinMaxPriorityQueue.orderedBy(Ordering.by[ParseForestItem, Double](_.prob).reverse).maximumSize(kBest)
    val q0 = qb.create[ParseForestItem]
    val q1 = qb.create[ParseForestItem]
    val q2 = qb.create[ParseForestItem]

    (1 until len).foreach { j =>
      (0 until len - j).foreach { s =>
        val t = s + j
        val (type1, type2) = staticTypes.map(ts => (ts(s)(t), ts(t)(s))).getOrElse((0, 0))

        // case when r == s
        val b1 = pf.complete(s)(s)
        val c1 = pf.complete(t)(s + 1)

        val sstFv = fvsTr(s)(s)(t).cat(fvsSi(s)(t)(0))
        val sstProb = probsTr(s)(s)(t) + probsSi(s)(t)(0)

        pf.bestPairs(b1, c1)(_.prob).take(kBest).foreach { case ((b, c), v) =>
          var finProb = v + probs(s)(t)(0) + sstProb
          var finFv = fvs(s)(t)(0).cat(sstFv)

          if (this.pipe.labeled) {
            finFv = fvsNt(s)(type1)(0)(1).cat(fvsNt(t)(type1)(0)(0).cat(finFv))
            finProb += probsNt(s)(type1)(0)(1) + probsNt(t)(type1)(0)(0)
          }
          q0.add(ArcItem(s, t, type1, finProb, finFv, b, c))
        }

        // case when r == t
        val b2 = pf.complete(s)(t - 1)
        val c2 = pf.complete(t)(t) 

        val sttFv = fvsTr(t)(t)(s).cat(fvsSi(t)(s)(0))
        val sttProb = probsTr(t)(t)(s) + probsSi(t)(s)(0)

        pf.bestPairs(b2, c2)(_.prob).take(kBest).foreach { case ((b, c), v) =>
          var finProb = v + probs(s)(t)(1) + sttProb
          var finFv = fvs(s)(t)(1).cat(sttFv)

          if (this.pipe.labeled) {
            finFv = fvsNt(t)(type2)(1)(1).cat(fvsNt(s)(type2)(1)(0).cat(finFv))
            finProb += probsNt(t)(type2)(1)(1) + probsNt(s)(type2)(1)(0)
          }
          q1.add(ArcItem(t, s, type2, finProb, finFv, b, c))
        }

        (s until t).foreach { r =>
          // First case - create sibling.
          val bs = pf.complete(s)(r)
          val cs = pf.complete(t)(r + 1)

          pf.bestPairs(bs, cs)(_.prob).take(kBest).foreach { case ((b, c), _) =>
            q2.add(CompleteItem(b, c))
          }
        }

        val stuff = IndexedSeq.fill(q2.size)(q2.pollFirst)
        pf.other(s)(t) = stuff
        pf.other(t)(s) = stuff

        (s + 1 until t).foreach { r =>
          // s -> (r,t)
          val b1 = pf.incomplete(s)(r)
          val c1 = pf.other(r)(t)
 
          pf.bestPairs(b1, c1)(_.prob).take(kBest).foreach { case ((b, c), v) =>
            var finProb = v + probs(s)(t)(0) + probsTr(s)(r)(t) + probsSi(r)(t)(1)
            var finFv = fvs(s)(t)(0).cat(fvsTr(s)(r)(t).cat(fvsSi(r)(t)(1)))

            if (this.pipe.labeled) {
              finFv = fvsNt(s)(type1)(0)(1).cat(fvsNt(t)(type1)(0)(0).cat(finFv))
              finProb += probsNt(s)(type1)(0)(1) + probsNt(t)(type1)(0)(0)
            }
            q0.add(ArcItem(s, t, type1, finProb, finFv, b, c)) 
          }

          // s -> (r,t)
          val b2 = pf.other(r)(s)
          val c2 = pf.incomplete(t)(r)

          pf.bestPairs(b2, c2)(_.prob).take(kBest).foreach { case ((b, c), v) =>
            var finProb = v + probs(s)(t)(1) + probsTr(t)(r)(s) + probsSi(r)(s)(1)
            var finFv = fvs(s)(t)(1).cat(fvsTr(t)(r)(s).cat(fvsSi(r)(s)(1)))

            if (this.pipe.labeled) {
              finFv = fvsNt(t)(type2)(1)(1).cat(fvsNt(s)(type2)(1)(0).cat(finFv))
              finProb += probsNt(t)(type2)(1)(1) + probsNt(s)(type2)(1)(0)
            }
            q1.add(ArcItem(t, s, type2, finProb, finFv, b, c)) 
          }
        }

        pf.incomplete(s)(t) = IndexedSeq.fill(q0.size)(q0.pollFirst)
        pf.incomplete(t)(s) = IndexedSeq.fill(q1.size)(q1.pollFirst)

        (s to t).foreach { r =>
          if (r != s) {
            val bs = pf.incomplete(s)(r)
            val cs = pf.complete(r)(t)

            pf.bestPairs(bs, cs)(_.prob).take(kBest).foreach { case ((b, c), _) =>
              q0.add(CompleteItem(b, c))
            }
          }

          if (r != t) {
            val bs = pf.complete(r)(s)
            val cs = pf.incomplete(t)(r)

            pf.bestPairs(bs, cs)(_.prob).take(kBest).foreach { case ((b, c), _) =>
              q1.add(CompleteItem(b, c))
            }
          }
        }
        pf.complete(s)(t) = IndexedSeq.fill(q0.size)(q0.pollFirst)
        pf.complete(t)(s) = IndexedSeq.fill(q1.size)(q1.pollFirst)
      }
    }
    pf.getBestParses
  }
}

