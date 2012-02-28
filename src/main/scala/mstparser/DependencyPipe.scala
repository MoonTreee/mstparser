package mstparser

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

import scala.reflect.BeanProperty

import mstparser.io._

class DependencyPipe(
  @BeanProperty protected val options: ParserOptions,
  @BeanProperty var dataAlphabet: Alphabet,
  @BeanProperty var typeAlphabet: Alphabet
) extends old.DependencyPipe {
  def this(options: ParserOptions) = this(options, new Alphabet(), new Alphabet())

  private val depReader = DependencyReader.createDependencyReader(this.options.format, this.options.discourseMode)
  private var depWriter: DependencyWriter = _
  protected var types: Array[String] = _
  protected var labeled: Boolean = _
  private var instances: IndexedSeq[DependencyInstance] = _

  def getLabeled = this.labeled
  def getTypes = this.types

  def initInputFile(file: String) {
    this.labeled = this.depReader.startReading(file)
  }

  def initOutputFile(file: String) {
	  this.depWriter = DependencyWriter.createDependencyWriter(this.options.format, this.labeled)
    this.depWriter.startWriting(file)
  }

  def outputInstance(instance: DependencyInstance) {
    this.depWriter.write(instance)
  }

  def close() {
    if (this.depWriter != null)
      this.depWriter.finishWriting()
  }

  def closeAlphabets() {
    this.dataAlphabet.setGrowing(false)
    this.typeAlphabet.setGrowing(false)
    this.types = Array.ofDim[String](this.typeAlphabet.size)
    val keys = this.typeAlphabet.toArray
    keys.foreach { key =>
      this.types(this.typeAlphabet.lookupIndex(key)) = key
    }
  }

  def getType(typeIndex: Int) = this.types(typeIndex)

  def nextInstance = if (!this.depReader.hasNext) null else {
    val instance = this.depReader.next
    if (instance.forms == null) null else {
      instance.setFeatureVector(this.createFeatureVector(instance))

      val spans = new StringBuffer(instance.heads.length * 5)
      instance.heads.zip(instance.deprels).zipWithIndex.tail.foreach { case ((head, label), i) =>
	      spans.append(head).append("|").append(i).append(":").append(this.typeAlphabet.lookupIndex(label)).append(" ")
      }

      instance.setParseTree(spans.substring(0, spans.length - 1))
      instance
    }
  }

  def add(f: String, fv: FeatureVector) {
    this.add(f, 1.0, fv)
  }

  def add(f: String, v: Double, fv: FeatureVector) {
    val i = this.dataAlphabet.lookupIndex(f)
    if (i >= 0) fv.add(i, v)
  }

  private def createAlphabet(file: String) {
    print("Creating Alphabet ... ")

    this.labeled = this.depReader.startReading(file)

    depReader.foreach { instance =>
      instance.deprels.foreach(this.typeAlphabet.lookupIndex(_))
      this.createFeatureVector(instance)
    }

    this.closeAlphabets()
    println("Done.")
  }

  def createFeatureVector(instance: DependencyInstance) = {
    val fv = new FeatureVector

    instance.heads.zip(instance.deprels).zipWithIndex.filter(_._1._1 > -1).foreach {
      case ((h, l), i) =>
        val small = math.min(h, i)
        val large = math.max(h, i)
        val attR = i >= h
        this.addCoreFeatures(instance, small, large, attR, fv)
        if (this.labeled) {
          this.addLabeledFeatures(instance, i, l, attR, true, fv)
          this.addLabeledFeatures(instance, h, l, attR, false, fv)
        }
    }

    this.addExtendedFeatures(instance, fv)

	  fv
  }
  
  private def addExtendedFeatures(instance: DependencyInstance, fv: FeatureVector) {}

  def createInstances(file: String, featFile: File) = {
    this.createAlphabet(file)

    println("Num Features: " + dataAlphabet.size)

    this.labeled = this.depReader.startReading(file)

    val out = if (this.options.createForest)
      new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream(featFile)))
    else null

    println("Creating Feature Vector Instances: ")
    this.instances = depReader.zipWithIndex.map { case (instance, i) =>
	    print(i + " ")
	    
	    instance.setFeatureVector(this.createFeatureVector(instance))
      instance.setParseTree(
        instance.heads.tail.zip(instance.deprels.tail).zipWithIndex.map {
          case ((h, l), j) => "%d|%d:%d".format(h, j + 1, this.typeAlphabet.lookupIndex(l))
        }.mkString(" ")
	    )

	    if (this.options.createForest) this.writeInstance(instance, out)
      instance
    }.toIndexedSeq

    println()

    this.closeAlphabets()

    if (this.options.createForest) out.close()
    this.instances.map(_.length)
  }

  protected def writeInstance(instance: DependencyInstance, out: ObjectOutputStream) {
    var c = 0

    (0 until instance.length).foreach { w1 =>
      ((w1 + 1) until instance.length).foreach { w2 =>
        val prodFV1 = new FeatureVector
        this.addCoreFeatures(instance, w1, w2, true, prodFV1)
        out.writeObject(prodFV1.keys)
        c += prodFV1.keys.length

        val prodFV2 = new FeatureVector
        this.addCoreFeatures(instance, w1, w2, false, prodFV2)
        out.writeObject(prodFV2.keys)
        c += prodFV2.keys.length
      }
    }

    out.writeInt(-3)

    if (this.labeled) {
      (0 until instance.length).foreach { w1 =>
        this.types.foreach { t =>
          val prodFV1 = new FeatureVector
          this.addLabeledFeatures(instance, w1, t, true, true, prodFV1)
          out.writeObject(prodFV1.keys)
          c += prodFV1.keys.length

          val prodFV2 = new FeatureVector
          this.addLabeledFeatures(instance, w1, t, true, false, prodFV2)
          out.writeObject(prodFV2.keys)
          c += prodFV2.keys.length

          val prodFV3 = new FeatureVector
          this.addLabeledFeatures(instance, w1, t, false, true, prodFV3)
          out.writeObject(prodFV3.keys)
          c += prodFV3.keys.length

          val prodFV4 = new FeatureVector
          this.addLabeledFeatures(instance, w1, t, false, false, prodFV4)
          out.writeObject(prodFV4.keys)
          c += prodFV4.keys.length
        }
      }
		  out.writeInt(-3)
    }

    this.writeExtendedFeatures(instance, out)

    out.writeObject(instance.getFeatureVector.keys)
    out.writeInt(-4)

    out.writeObject(instance)
    out.writeInt(-1)
    out.reset()

    println("Written: " + c.toString)
  }

  private def writeExtendedFeatures(instance: DependencyInstance, out: ObjectOutputStream) {}

  def fillFeatureVectors(instance: DependencyInstance,
    fvs: Array[Array[Array[FeatureVector]]],
    probs: Array[Array[Array[Double]]],
    fvsNt: Array[Array[Array[Array[FeatureVector]]]],
    probsNt: Array[Array[Array[Array[Double]]]],
    params: Parameters
  ) {
    (0 until instance.length).foreach { w1 =>
      ((w1 + 1) until instance.length).foreach { w2 =>
        val prodFV1 = new FeatureVector
        this.addCoreFeatures(instance, w1, w2, true, prodFV1)
        fvs(w1)(w2)(0) = prodFV1
        probs(w1)(w2)(0) = params.getScore(prodFV1)

        val prodFV2 = new FeatureVector
        this.addCoreFeatures(instance, w1, w2, false, prodFV2)
        fvs(w1)(w2)(1) = prodFV2
        probs(w1)(w2)(1) = params.getScore(prodFV2)
      }
    }

    if (this.labeled) {
      (0 until instance.length).foreach { w1 =>
        this.types.zipWithIndex.foreach { case (t, i) =>
          val prodFV1 = new FeatureVector
          this.addLabeledFeatures(instance, w1, t, true, true, prodFV1)
          fvsNt(w1)(i)(0)(0) = prodFV1
          probsNt(w1)(i)(0)(0) = params.getScore(prodFV1)

          val prodFV2 = new FeatureVector
          this.addLabeledFeatures(instance, w1, t, true, false, prodFV2)
          fvsNt(w1)(i)(0)(1) = prodFV2
          probsNt(w1)(i)(0)(1) = params.getScore(prodFV2)

          val prodFV3 = new FeatureVector
          this.addLabeledFeatures(instance, w1, t, false, true, prodFV3)
          fvsNt(w1)(i)(1)(0) = prodFV3
          probsNt(w1)(i)(1)(0) = params.getScore(prodFV3)

          val prodFV4 = new FeatureVector
          this.addLabeledFeatures(instance, w1, t, false, false, prodFV4)
          fvsNt(w1)(i)(1)(1) = prodFV4
          probsNt(w1)(i)(1)(1) = params.getScore(prodFV4)
        }
      }
    }
  }

  def readInstance(in: ObjectInputStream, length: Int,
    fvs: Array[Array[Array[FeatureVector]]],
    probs: Array[Array[Array[Double]]],
    fvsNt: Array[Array[Array[Array[FeatureVector]]]],
    probsNt: Array[Array[Array[Array[Double]]]],
    params: Parameters
  ) = {
	  try {
      (0 until length).foreach { w1 =>
        ((w1 + 1) until length).foreach { w2 =>
			    val prodFV1 = FeatureVector.fromKeys(in.readObject().asInstanceOf[Array[Int]])
          fvs(w1)(w2)(0) = prodFV1
          probs(w1)(w2)(0) = params.getScore(prodFV1)

			    val prodFV2 = FeatureVector.fromKeys(in.readObject().asInstanceOf[Array[Int]])
          fvs(w1)(w2)(1) = prodFV2
          probs(w1)(w2)(1) = params.getScore(prodFV2)
        }
      }

	    if (in.readInt() != -3) { println("Error reading file."); sys.exit(0) }

      if (this.labeled) {
        (0 until length).foreach { w1 =>
          this.types.zipWithIndex.foreach { case (t, i) =>
			      val prodFV1 = FeatureVector.fromKeys(in.readObject().asInstanceOf[Array[Int]])
            fvsNt(w1)(i)(0)(0) = prodFV1
            probsNt(w1)(i)(0)(0) = params.getScore(prodFV1)

			      val prodFV2 = FeatureVector.fromKeys(in.readObject().asInstanceOf[Array[Int]])
            fvsNt(w1)(i)(0)(1) = prodFV2
            probsNt(w1)(i)(0)(1) = params.getScore(prodFV2)

			      val prodFV3 = FeatureVector.fromKeys(in.readObject().asInstanceOf[Array[Int]])
            fvsNt(w1)(i)(1)(0) = prodFV3
            probsNt(w1)(i)(1)(0) = params.getScore(prodFV3)

			      val prodFV4 = FeatureVector.fromKeys(in.readObject().asInstanceOf[Array[Int]])
            fvsNt(w1)(i)(1)(1) = prodFV4
            probsNt(w1)(i)(1)(1) = params.getScore(prodFV4)
          }
        }

	      if (in.readInt() != -3) { println("Error reading file."); sys.exit(0) }
      }

			val nFv = FeatureVector.fromKeys(in.readObject().asInstanceOf[Array[Int]])
	    if (in.readInt() != -4) { println("Error reading file."); sys.exit(0) }

	    val instance = in.readObject().asInstanceOf[DependencyInstance]
	    instance.setFeatureVector(nFv)

	    if (in.readInt() != -1) { println("Error reading file."); sys.exit(0) }

	    instance
    } catch { case e: ClassNotFoundException => println("Error reading file."); sys.exit(0) } 
	} 

/*
    public Alphabet dataAlphabet;
	
    public Alphabet typeAlphabet;

    private DependencyReader depReader;
    private DependencyWriter depWriter;

    public String[] types;
    public int[] typesInt;
	
    public boolean labeled = false;
    private boolean isCONLL = true;

    private ParserOptions options;

    public DependencyPipe (ParserOptions options) throws IOException {
	this.options = options;

	if (!options.format.equals("CONLL"))
	    isCONLL = false;

	dataAlphabet = new Alphabet();
	typeAlphabet = new Alphabet();

	depReader = DependencyReader.createDependencyReader(options.format, options.discourseMode);
    }

    public void addCoreFeatures(mstparser.DependencyInstance instance,
				int small,
				int large,
				boolean attR,
				FeatureVector fv) {

	String[] forms = instance.forms;
	String[] pos = instance.postags;
	String[] posA = instance.cpostags;

	String att = attR ? "RA" : "LA";

	int dist = Math.abs(large-small);
	String distBool = "0";
	if (dist > 10)
	    distBool = "10";
	else if (dist > 5)
	    distBool = "5";
	else
	    distBool = Integer.toString(dist-1);
		
	String attDist = "&"+att+"&"+distBool;

	addLinearFeatures("POS", pos, small, large, attDist, fv);
	addLinearFeatures("CPOS", posA, small, large, attDist, fv);
		

	//////////////////////////////////////////////////////////////////////
	
	int headIndex = small;
	int childIndex = large;
	if (!attR) {
	    headIndex = large;
	    childIndex = small;
	}

	addTwoObsFeatures("HC", forms[headIndex], pos[headIndex], 
			  forms[childIndex], pos[childIndex], attDist, fv);

	if (isCONLL) {

	    addTwoObsFeatures("HCA", forms[headIndex], posA[headIndex], 
	    		      forms[childIndex], posA[childIndex], attDist, fv);
	    
	    addTwoObsFeatures("HCC", instance.lemmas[headIndex], pos[headIndex], 
	    		      instance.lemmas[childIndex], pos[childIndex], 
	    		      attDist, fv);
	    
	    addTwoObsFeatures("HCD", instance.lemmas[headIndex], posA[headIndex], 
			      instance.lemmas[childIndex], posA[childIndex], 
			      attDist, fv);

	    if (options.discourseMode) {
		// Note: The features invoked here are designed for
		// discourse parsing (as opposed to sentential
		// parsing). It is conceivable that they could help for
		// sentential parsing, but current testing indicates that
		// they hurt sentential parsing performance.

		addDiscourseFeatures(instance, small, large,
				     headIndex, childIndex, 
				     attDist, fv);

	    } else {
		// Add in features from the feature lists. It assumes
		// the feature lists can have different lengths for
		// each item. For example, nouns might have a
		// different number of morphological features than
		// verbs.
	    
		for (int i=0; i<instance.feats[headIndex].length; i++) {
		    for (int j=0; j<instance.feats[childIndex].length; j++) {
			addTwoObsFeatures("FF"+i+"*"+j, 
					  instance.forms[headIndex], 
					  instance.feats[headIndex][i],
					  instance.forms[childIndex], 
					  instance.feats[childIndex][j], 
					  attDist, fv);
			
			addTwoObsFeatures("LF"+i+"*"+j, 
					  instance.lemmas[headIndex], 
					  instance.feats[headIndex][i],
					  instance.lemmas[childIndex], 
					  instance.feats[childIndex][j], 
					  attDist, fv);
		    }
		}
	    }

	} else {
	    // We are using the old MST format.  Pick up stem features
	    // the way they used to be done. This is kept for
	    // replicability of results for old versions.
	    int hL = forms[headIndex].length();
	    int cL = forms[childIndex].length();
	    if (hL > 5 || cL > 5) {
		addOldMSTStemFeatures(instance.lemmas[headIndex], 
				      pos[headIndex],
				      instance.lemmas[childIndex], 
				      pos[childIndex],
				      attDist, hL, cL, fv);
	    }
	}				       
		
    }
 
    private final void addLinearFeatures(String type, String[] obsVals, 
					 int first, int second,
					 String attachDistance,
					 FeatureVector fv) {
	
	String pLeft = first > 0 ? obsVals[first-1] : "STR";
	String pRight = second < obsVals.length-1 ? obsVals[second+1] : "END";
	String pLeftRight = first < second-1 ? obsVals[first+1] : "MID";
	String pRightLeft = second > first+1 ? obsVals[second-1] : "MID";

	// feature posR posMid posL
	StringBuilder featPos = 
	    new StringBuilder(type+"PC="+obsVals[first]+" "+obsVals[second]);

	for(int i = first+1; i < second; i++) {
	    String allPos = featPos.toString() + ' ' + obsVals[i];
	    add(allPos, fv);
	    add(allPos+attachDistance, fv);

	}

	addCorePosFeatures(type+"PT", pLeft, obsVals[first], pLeftRight, 
				pRightLeft, obsVals[second], pRight, attachDistance, fv);

    }


    private final void 
	addCorePosFeatures(String prefix,
			   String leftOf1, String one, String rightOf1, 
			   String leftOf2, String two, String rightOf2, 
			   String attachDistance, 
			   FeatureVector fv) {

	// feature posL-1 posL posR posR+1

	add(prefix+"="+leftOf1+" "+one+" "+two+"*"+attachDistance, fv);

	StringBuilder feat = 
	    new StringBuilder(prefix+"1="+leftOf1+" "+one+" "+two);
	add(feat.toString(), fv);
	feat.append(' ').append(rightOf2);
	add(feat.toString(), fv);
	feat.append('*').append(attachDistance);
	add(feat.toString(), fv);

	feat = new StringBuilder(prefix+"2="+leftOf1+" "+two+" "+rightOf2);
	add(feat.toString(), fv);
	feat.append('*').append(attachDistance);
	add(feat.toString(), fv);
	
	feat = new StringBuilder(prefix+"3="+leftOf1+" "+one+" "+rightOf2);
	add(feat.toString(), fv);
	feat.append('*').append(attachDistance);
	add(feat.toString(), fv);
	
	feat = new StringBuilder(prefix+"4="+one+" "+two+" "+rightOf2);
	add(feat.toString(), fv);
	feat.append('*').append(attachDistance);
	add(feat.toString(), fv);

	/////////////////////////////////////////////////////////////
	prefix = "A"+prefix;

	// feature posL posL+1 posR-1 posR
	add(prefix+"1="+one+" "+rightOf1+" "+leftOf2+"*"+attachDistance, fv);

	feat = new StringBuilder(prefix+"1="+one+" "+rightOf1+" "+leftOf2);
	add(feat.toString(), fv);
	feat.append(' ').append(two);
	add(feat.toString(), fv);
	feat.append('*').append(attachDistance);
	add(feat.toString(), fv);

	feat = new StringBuilder(prefix+"2="+one+" "+rightOf1+" "+two);
	add(feat.toString(), fv);
	feat.append('*').append(attachDistance);
	add(feat.toString(), fv);
	
	feat = new StringBuilder(prefix+"3="+one+" "+leftOf2+" "+two);
	add(feat.toString(), fv);
	feat.append('*').append(attachDistance);
	add(feat.toString(), fv);
	
	feat = new StringBuilder(prefix+"4="+rightOf1+" "+leftOf2+" "+two);
	add(feat.toString(), fv);
	feat.append('*').append(attachDistance);
	add(feat.toString(), fv);

	///////////////////////////////////////////////////////////////
	prefix = "B"+prefix;

	//// feature posL-1 posL posR-1 posR
	feat = new StringBuilder(prefix+"1="+leftOf1+" "+one+" "+leftOf2+" "+two);
	add(feat.toString(), fv);
	feat.append('*').append(attachDistance);
	add(feat.toString(), fv);

	//// feature posL posL+1 posR posR+1
	feat = new StringBuilder(prefix+"2="+one+" "+rightOf1+" "+two+" "+rightOf2);
	add(feat.toString(), fv);
	feat.append('*').append(attachDistance);
	add(feat.toString(), fv);

    }



    private final void addTwoObsFeatures(String prefix, 
					 String item1F1, String item1F2, 
					 String item2F1, String item2F2, 
					 String attachDistance,
					 FeatureVector fv) {

	StringBuilder feat = new StringBuilder(prefix+"2FF1="+item1F1);
	add(feat.toString(), fv);
	feat.append('*').append(attachDistance);
	add(feat.toString(), fv);
	
	feat = new StringBuilder(prefix+"2FF1="+item1F1+" "+item1F2);
	add(feat.toString(), fv);
	feat.append('*').append(attachDistance);
	add(feat.toString(), fv);

	feat = new StringBuilder(prefix+"2FF1="+item1F1+" "+item1F2+" "+item2F2);
	add(feat.toString(), fv);
	feat.append('*').append(attachDistance);
	add(feat.toString(), fv);

	feat = new StringBuilder(prefix+"2FF1="+item1F1+" "+item1F2+" "+item2F2+" "+item2F1);
	add(feat.toString(), fv);
	feat.append('*').append(attachDistance);
	add(feat.toString(), fv);
	
	feat = new StringBuilder(prefix+"2FF2="+item1F1+" "+item2F1);
	add(feat.toString(), fv);
	feat.append('*').append(attachDistance);
	add(feat.toString(), fv);

	feat = new StringBuilder(prefix+"2FF3="+item1F1+" "+item2F2);
	add(feat.toString(), fv);
	feat.append('*').append(attachDistance);
	add(feat.toString(), fv);


	feat = new StringBuilder(prefix+"2FF4="+item1F2+" "+item2F1);
	add(feat.toString(), fv);
	feat.append('*').append(attachDistance);
	add(feat.toString(), fv);

	feat = new StringBuilder(prefix+"2FF4="+item1F2+" "+item2F1+" "+item2F2);
	add(feat.toString(), fv);
	feat.append('*').append(attachDistance);
	add(feat.toString(), fv);

	feat = new StringBuilder(prefix+"2FF5="+item1F2+" "+item2F2);
	add(feat.toString(), fv);
	feat.append('*').append(attachDistance);
	add(feat.toString(), fv);

	feat = new StringBuilder(prefix+"2FF6="+item2F1+" "+item2F2);
	add(feat.toString(), fv);
	feat.append('*').append(attachDistance);
	add(feat.toString(), fv);

	feat = new StringBuilder(prefix+"2FF7="+item1F2);
	add(feat.toString(), fv);
	feat.append('*').append(attachDistance);
	add(feat.toString(), fv);
	
	feat = new StringBuilder(prefix+"2FF8="+item2F1);
	add(feat.toString(), fv);
	feat.append('*').append(attachDistance);
	add(feat.toString(), fv);
	
	feat = new StringBuilder(prefix+"2FF9="+item2F2);
	add(feat.toString(), fv);
	feat.append('*').append(attachDistance);
	add(feat.toString(), fv);
	
    }

    public void addLabeledFeatures(mstparser.DependencyInstance instance,
				   int word,
				   String type,
				   boolean attR,
				   boolean childFeatures,
				   FeatureVector fv) {
		
	if(!labeled) 
	    return;

	String[] forms = instance.forms;
	String[] pos = instance.postags;
	    
	String att = "";
	if(attR)
	    att = "RA";
	else
	    att = "LA";

	att+="&"+childFeatures;
		
	String w = forms[word];
	String wP = pos[word];

	String wPm1 = word > 0 ? pos[word-1] : "STR";
	String wPp1 = word < pos.length-1 ? pos[word+1] : "END";

	add("NTS1="+type+"&"+att,fv);
	add("ANTS1="+type,fv);
	for(int i = 0; i < 2; i++) {
	    String suff = i < 1 ? "&"+att : "";
	    suff = "&"+type+suff;

	    add("NTH="+w+" "+wP+suff,fv);
	    add("NTI="+wP+suff,fv);
	    add("NTIA="+wPm1+" "+wP+suff,fv);
	    add("NTIB="+wP+" "+wPp1+suff,fv);
	    add("NTIC="+wPm1+" "+wP+" "+wPp1+suff,fv);
	    add("NTJ="+w+suff,fv); //this

	}
    }


    private void addDiscourseFeatures (mstparser.DependencyInstance instance, 
				       int small,
				       int large,
				       int headIndex,
				       int childIndex,
				       String attDist,
				       FeatureVector fv) {
    
	addLinearFeatures("FORM", instance.forms, small, large, attDist, fv);
	addLinearFeatures("LEMMA", instance.lemmas, small, large, attDist, fv);
	
	addTwoObsFeatures("HCB1", instance.forms[headIndex], 
			  instance.lemmas[headIndex],
			  instance.forms[childIndex], 
			  instance.lemmas[childIndex], 
			  attDist, fv);
	
	addTwoObsFeatures("HCB2", instance.forms[headIndex], 
			  instance.lemmas[headIndex],
			  instance.forms[childIndex], 
			  instance.postags[childIndex], 
			  attDist, fv);
	
	addTwoObsFeatures("HCB3", instance.forms[headIndex], 
			  instance.lemmas[headIndex],
			  instance.forms[childIndex], 
			  instance.cpostags[childIndex], 
			  attDist, fv);
	
	addTwoObsFeatures("HC2", instance.forms[headIndex], 
			  instance.postags[headIndex], 
			  instance.forms[childIndex], 
			  instance.cpostags[childIndex], attDist, fv);
	
	addTwoObsFeatures("HCC2", instance.lemmas[headIndex], 
			  instance.postags[headIndex], 
			  instance.lemmas[childIndex], 
			  instance.cpostags[childIndex], 
			  attDist, fv);
	
	
	//// Use this if your extra feature lists all have the same length.
	for (int i=0; i<instance.feats.length; i++) {
	
		addLinearFeatures("F"+i, instance.feats[i], small, large, attDist, fv);
	
		addTwoObsFeatures("FF"+i, 
				  instance.forms[headIndex], 
				  instance.feats[i][headIndex],
				  instance.forms[childIndex], 
				  instance.feats[i][childIndex],
				  attDist, fv);
		
		addTwoObsFeatures("LF"+i, 
				  instance.lemmas[headIndex], 
				  instance.feats[i][headIndex],
				  instance.lemmas[childIndex], 
				  instance.feats[i][childIndex],
				  attDist, fv);
		
		addTwoObsFeatures("PF"+i, 
				  instance.postags[headIndex], 
				  instance.feats[i][headIndex],
				  instance.postags[childIndex], 
				  instance.feats[i][childIndex],
				  attDist, fv);
		
		addTwoObsFeatures("CPF"+i, 
				  instance.cpostags[headIndex], 
				  instance.feats[i][headIndex],
				  instance.cpostags[childIndex], 
				  instance.feats[i][childIndex],
				  attDist, fv);
		
		
		for (int j=i+1; j<instance.feats.length; j++) {
		
		    addTwoObsFeatures("CPF"+i+"_"+j, 
				      instance.feats[i][headIndex],
				      instance.feats[j][headIndex],
				      instance.feats[i][childIndex],
				      instance.feats[j][childIndex],
				      attDist, fv);
		
		}
	
		for (int j=0; j<instance.feats.length; j++) {
	
		    addTwoObsFeatures("XFF"+i+"_"+j, 
				      instance.forms[headIndex],
				      instance.feats[i][headIndex],
				      instance.forms[childIndex],
				      instance.feats[j][childIndex],
				      attDist, fv);
	
		    addTwoObsFeatures("XLF"+i+"_"+j, 
				      instance.lemmas[headIndex],
				      instance.feats[i][headIndex],
				      instance.lemmas[childIndex],
				      instance.feats[j][childIndex],
				      attDist, fv);
	
		    addTwoObsFeatures("XPF"+i+"_"+j, 
				      instance.postags[headIndex],
				      instance.feats[i][headIndex],
				      instance.postags[childIndex],
				      instance.feats[j][childIndex],
				      attDist, fv);
	
	
		    addTwoObsFeatures("XCF"+i+"_"+j, 
				      instance.cpostags[headIndex],
				      instance.feats[i][headIndex],
				      instance.cpostags[childIndex],
				      instance.feats[j][childIndex],
				      attDist, fv);
	
	
		}
	
	}


	// Test out relational features
	if (options.useRelationalFeatures) {

	    //for (int rf_index=0; rf_index<2; rf_index++) {
	    for (int rf_index=0; 
		 rf_index<instance.relFeats.length; 
		 rf_index++) {
		
		String headToChild = 
		    "H2C"+rf_index+instance.relFeats[rf_index].getFeature(headIndex, childIndex);
	    
		addTwoObsFeatures("RFA1",
				  instance.forms[headIndex], 
				  instance.lemmas[headIndex],
				  instance.postags[childIndex],
				  headToChild,
				  attDist, fv);
		
		addTwoObsFeatures("RFA2",
				  instance.postags[headIndex], 
				  instance.cpostags[headIndex],
				  instance.forms[childIndex],
				  headToChild,
				  attDist, fv);
	    
	    	addTwoObsFeatures("RFA3",
				  instance.lemmas[headIndex], 
				  instance.postags[headIndex],
				  instance.forms[childIndex],
				  headToChild,
				  attDist, fv);
	    	
	    	addTwoObsFeatures("RFB1",
				  headToChild,
				  instance.postags[headIndex],
				  instance.forms[childIndex], 
				  instance.lemmas[childIndex],
				  attDist, fv);
	    	
	    	addTwoObsFeatures("RFB2",
				  headToChild,
				  instance.forms[headIndex],
				  instance.postags[childIndex], 
				  instance.cpostags[childIndex],
				  attDist, fv);
	    	
	    	addTwoObsFeatures("RFB3",
				  headToChild,
				  instance.forms[headIndex],
				  instance.lemmas[childIndex], 
				  instance.postags[childIndex],
				  attDist, fv);
		
	    }
	}
    }

    private final void
	addOldMSTStemFeatures(String hLemma, String headP, 
			      String cLemma, String childP, String attDist, 
			      int hL, int cL, FeatureVector fv) {

	String all = hLemma + " " + headP + " " + cLemma + " " + childP;
	String hPos = headP + " " + cLemma + " " + childP;
	String cPos = hLemma + " " + headP + " " + childP;
	String hP = headP + " " + cLemma;
	String cP = hLemma + " " + childP;
	String oPos = headP + " " + childP;
	String oLex = hLemma + " " + cLemma;
	
	add("SA="+all+attDist,fv); //this
	add("SF="+oLex+attDist,fv); //this
	add("SAA="+all,fv); //this
	add("SFF="+oLex,fv); //this
	
	if(cL > 5) {
	    add("SB="+hPos+attDist,fv);
	    add("SD="+hP+attDist,fv);
	    add("SK="+cLemma+" "+childP+attDist,fv);
	    add("SM="+cLemma+attDist,fv); //this
	    add("SBB="+hPos,fv);
	    add("SDD="+hP,fv);
	    add("SKK="+cLemma+" "+childP,fv);
	    add("SMM="+cLemma,fv); //this
	}
	if(hL > 5) {
	    add("SC="+cPos+attDist,fv);
	    add("SE="+cP+attDist,fv);
	    add("SH="+hLemma+" "+headP+attDist,fv);
	    add("SJ="+hLemma+attDist,fv); //this
	    
	    add("SCC="+cPos,fv);
	    add("SEE="+cP,fv);
	    add("SHH="+hLemma+" "+headP,fv);
	    add("SJJ="+hLemma,fv); //this
	}

    }*/	
}

