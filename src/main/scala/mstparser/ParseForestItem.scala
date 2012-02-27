package mstparser

class ParseForestItem(
  private val s: Int,
  private val r: Int,
  private val t: Int,
  val label: Int,
  val dir: Int,
  val comp: Int,
  val prob: Double,
  val fv: FeatureVector,
  val children: Option[(ParseForestItem, ParseForestItem)]
) {
  def this(s: Int, r: Int, t: Int, label: Int, dir: Int, comp: Int) =
    this(s, r, t, label, dir, comp, Double.NegativeInfinity, null, None)

  def this(s: Int, label: Int, dir: Int, prob: Double, fv: FeatureVector) =
    this(s, 0, 0, label, dir, 0, prob, fv, None)

  def this(s: Int, label: Int, dir: Int) =
    this(s, label, dir, Double.NegativeInfinity, null)

  def getFeatureVector: FeatureVector = this.children.map { case (left, right) =>
    this.fv.cat(left.getFeatureVector.cat(right.getFeatureVector))
  }.getOrElse(this.fv)

  def getDepString: String = this.children.map { case (left, right) =>
    val ld = left.getDepString
    val rd = right.getDepString
    val cs = (ld + " " + rd).trim

    if (this.comp == 0) cs
    else if (this.dir == 0) "%s %d|%d:%d".format(cs, this.s, this.t, this.label).trim
    else "%d|%d:%d %s".format(this.t, this.s, this.label, cs).trim 
  }.getOrElse("")
}
