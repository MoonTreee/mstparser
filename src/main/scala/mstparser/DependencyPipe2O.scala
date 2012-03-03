package mstparser

import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class DependencyPipe2O(options: ParserOptions) extends old.DependencyPipe2O(options) {
  protected override def addExtendedFeatures(instance: DependencyInstance, fv: FeatureVector) {
    val heads = instance.heads.zipWithIndex

    heads.filter { case (h, i) => h > -1 || i == 0 }.foreach {
      case (h, i) =>
        var prev = i
        heads.drop(i + 1).filter(_._1 == i).foreach { case (_, j) =>
          this.addTripFeatures(instance, i, prev, j, fv)
          this.addSiblingFeatures(instance, prev, j, prev == i, fv)
          prev = j
        }

        prev = i
        heads.take(i).reverse.filter(_._1 == i).foreach { case (_, j) =>
          this.addTripFeatures(instance, i, prev, j, fv)
          this.addSiblingFeatures(instance, prev, j, prev == i, fv)
          prev = j
        }
    }
  }

  protected def addTripFeatures(
    instance: DependencyInstance,
    par: Int, ch1: Int, ch2: Int,
    fv: FeatureVector
  ) {
    // ch1 is always the closest to par.
    val dir = if (par > ch2) "RA" else "LA"
    val ch1Pos = if (ch1 == par) "STPOS" else instance.postags(ch1)
    val ch2Pos = instance.postags(ch2)
    val pTrip = instance.postags(par) + "_" + ch1Pos + "_" + ch2Pos

    this.add("POS_TRIP=" + pTrip + "_" + dir, 1.0, fv)
    this.add("APOS_TRIP=" + pTrip, 1.0, fv)
  }

  protected override def writeExtendedFeatures(instance: DependencyInstance, out: ObjectOutputStream) {
    val len = instance.length

    (0 until len).foreach { w1 =>
      for {
        w2 <- w1 until len
        w3 <- w2 + 1 until len
      } {
        val fv = new FeatureVector
        this.addTripFeatures(instance, w1, w2, w3, fv)
        out.writeObject(fv.keys)
      }

        for {
          w2 <- w1 to 0 by -1
          w3 <- w2 - 1 to 0 by -1
        } {
          val fv = new FeatureVector
          this.addTripFeatures(instance, w1, w2, w3, fv)
          out.writeObject(fv.keys)
        }
      }

      out.writeInt(-3)

      for {
        w1 <- 0 until len
        w2 <- 0 until len if w1 != w2
        wh <- 0 until 1
      } {
        val fv = new FeatureVector
        this.addSiblingFeatures(instance, w1, w2, wh == 0, fv)
        out.writeObject(fv.keys)
      }

      out.writeInt(-3)
  }

  def readInstance(
    in: ObjectInputStream, len: Int,
    fvs: Array[Array[Array[FeatureVector]]],
    probs: Array[Array[Array[Double]]],
    fvsTr: Array[Array[Array[FeatureVector]]],
    probsTr: Array[Array[Array[Double]]],
    fvsSi: Array[Array[Array[FeatureVector]]],
    probsSi: Array[Array[Array[Double]]],
    fvsNt: Array[Array[Array[Array[FeatureVector]]]],
    probsNt: Array[Array[Array[Array[Double]]]],
    params: Parameters
  ) {
    try {
      super.readInstance(in, len, fvs, probs, fvsNt, probsNt, params)

      (0 until len).foreach { w1 =>
        for {
          w2 <- w1 until len
          w3 <- w2 + 1 until len
        } {
          fvsTr(w1)(w2)(w3) = FeatureVector.fromKeys(in.readObject().asInstanceOf[Array[Int]])
          probsTr(w1)(w2)(w3) = params.getScore(fvsTr(w1)(w2)(w3))
        }

        for {
          w2 <- w1 to 0 by -1
          w3 <- w2 - 1 to 0 by -1
        } {
          fvsTr(w1)(w2)(w3) = FeatureVector.fromKeys(in.readObject().asInstanceOf[Array[Int]])
          probsTr(w1)(w2)(w3) = params.getScore(fvsTr(w1)(w2)(w3))
        }
      }

      if (in.readInt() != -3) { println("Error reading file."); sys.exit(0) }

      for {
        w1 <- 0 until len
        w2 <- 0 until len if w1 != w2
        wh <- 0 until 1
      } {
        fvsSi(w1)(w2)(wh) = FeatureVector.fromKeys(in.readObject().asInstanceOf[Array[Int]])
        probsSi(w1)(w2)(wh) = params.getScore(fvsSi(w1)(w2)(wh))
      }

      if (in.readInt() != -3) { println("Error reading file."); sys.exit(0) }
    } catch { case e: IOException => println("Error reading file."); sys.exit(0) } 
  } 
}
