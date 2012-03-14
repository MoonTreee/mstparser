package mstparser

class ParseForestItem(
  private[this] val s: Int,
  private[this] val t: Int,
  private[this] val label: Int,
  private[this] val dir: Int,
  private[this] val comp: Int,
  val prob: Double,
  private[this] val fv: FeatureVector,
  val children: Option[(ParseForestItem, ParseForestItem)]
) {
  def this(s: Int, t: Int, label: Int, dir: Int, comp: Int) =
    this(s, t, label, dir, comp, Double.NegativeInfinity, null, None)

  def this(s: Int, label: Int, dir: Int, prob: Double, fv: FeatureVector) =
    this(s, 0, label, dir, 0, prob, fv, None)

  def this(s: Int, label: Int, dir: Int) =
    this(s, label, dir, Double.NegativeInfinity, null)

  def featureVector: FeatureVector = this.children.map { case (left, right) =>
    this.fv.cat(left.featureVector.cat(right.featureVector))
  }.getOrElse(this.fv)

  def depString: String = this.children.map { case (left, right) =>
    val ld = left.depString
    val rd = right.depString
    val cs = (ld + " " + rd).trim

    if (this.comp != 1) cs
    else if (this.dir == 0) "%s %d|%d:%d".format(cs, this.s, this.t, this.label).trim
    else "%d|%d:%d %s".format(this.t, this.s, this.label, cs).trim
  }.getOrElse("")
}

