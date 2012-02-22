package mstparser

object FeatureVector {
  def fromKeys(keys: Array[Int]) = {
    val v = new FeatureVector()
    keys.foreach(k => v.add(new Feature(k, 1.0)))
    v
  }
}

class FeatureVector(fv1: FeatureVector, fv2: FeatureVector, negSecond: Boolean) extends old.FeatureVector(fv1, fv2, negSecond) {
  def this(fv1: FeatureVector, fv2: FeatureVector) = this(fv1, fv2, false)
  def this(fv1: FeatureVector) = this(fv1, null)
  def this() = this(null) 

  def cat(that: FeatureVector) = new FeatureVector(this, that)
  def getDistVector(that: FeatureVector) = new FeatureVector(this, that, true)
}

/*
public class FeatureVector extends TLinkedList<Feature> {
    private FeatureVector subfv1 = null;
    private FeatureVector subfv2 = null;
    private boolean negateSecondSubFV = false;

    public FeatureVector () {}

    public FeatureVector (FeatureVector fv1) {
	subfv1 = fv1;
    }

    public FeatureVector (FeatureVector fv1, FeatureVector fv2) {
	subfv1 = fv1;
	subfv2 = fv2;
    }

    public FeatureVector (FeatureVector fv1, FeatureVector fv2, boolean negSecond) {
	subfv1 = fv1;
	subfv2 = fv2;
	negateSecondSubFV = negSecond;
    }

    public FeatureVector (int[] keys) {
	for (int i=0; i<keys.length; i++)
	    add(new Feature(keys[i],1.0));
    }

    public void add(int index, double value) {
	add(new Feature(index, value));
    }


    public int[] keys() {
	TIntArrayList keys = new TIntArrayList();
	addKeysToList(keys);
	return keys.toArray();
    }

    private void addKeysToList(TIntArrayList keys) {
	if (null != subfv1) {
	    subfv1.addKeysToList(keys);

	    if (null != subfv2)
		subfv2.addKeysToList(keys);
	}

	ListIterator it = listIterator();
	while (it.hasNext())
	    keys.add(((Feature)it.next()).getIndex());

    }


    public final double getScore(double[] parameters) {
	return getScore(parameters, false);
    }

    private final double getScore(double[] parameters, boolean negate) {
	double score = 0.0;

	if (null != subfv1) {
	    score += subfv1.getScore(parameters, negate);

	    if (null != subfv2) {
		if (negate) {
		    score += subfv2.getScore(parameters, !negateSecondSubFV);
		} else {
		    score += subfv2.getScore(parameters, negateSecondSubFV);
		}
	    }
	}

	ListIterator it = listIterator();

	if (negate) {
	    while (it.hasNext()) {
		Feature f = (Feature)it.next();
		score -= parameters[f.getIndex()]*f.getValue();
	    }
	} else {
	    while (it.hasNext()) {
		Feature f = (Feature)it.next();
		score += parameters[f.getIndex()]*f.getValue();
	    }
	}

	return score;
    }

    public void update(double[] parameters, double[] total, double alpha_k, double upd) {
	update(parameters, total, alpha_k, upd, false);
    }

    private final void update(double[] parameters, double[] total, 
			      double alpha_k, double upd, boolean negate) {

	if (null != subfv1) {
	    subfv1.update(parameters, total, alpha_k, upd, negate);

	    if (null != subfv2) {
		if (negate) {
		    subfv2.update(parameters, total, alpha_k, upd, !negateSecondSubFV);
		} else {
		    subfv2.update(parameters, total, alpha_k, upd, negateSecondSubFV);
		}
	    }
	}


	ListIterator it = listIterator();

	if (negate) {
	    while (it.hasNext()) {
		Feature f = (Feature)it.next();
		parameters[f.getIndex()] -= alpha_k*f.getValue();
		total[f.getIndex()] -= upd*alpha_k*f.getValue();
	    }
	} else {
	    while (it.hasNext()) {
		Feature f = (Feature)it.next();
		parameters[f.getIndex()] += alpha_k*f.getValue();
		total[f.getIndex()] += upd*alpha_k*f.getValue();
	    }
	}

    }

	
    public double dotProduct(FeatureVector fl2) {

	TIntDoubleHashMap hm1 = new TIntDoubleHashMap(this.size());
	addFeaturesToMap(hm1, false);
	hm1.compact();

	TIntDoubleHashMap hm2 = new TIntDoubleHashMap(fl2.size());
	fl2.addFeaturesToMap(hm2, false);
	hm2.compact();

	int[] keys = hm1.keys();

	double result = 0.0;
	for(int i = 0; i < keys.length; i++)
	    result += hm1.get(keys[i])*hm2.get(keys[i]);
		
	return result;
		
    }

    private void addFeaturesToMap(TIntDoubleHashMap map, boolean negate) {
	if (null != subfv1) {
	    subfv1.addFeaturesToMap(map, negate);

	    if (null != subfv2) {
		if (negate) {
		    subfv2.addFeaturesToMap(map, !negateSecondSubFV);
		} else {
		    subfv2.addFeaturesToMap(map, negateSecondSubFV);
		}
	    }
	}

	ListIterator it = listIterator();
	if (negate) {
	    while (it.hasNext()) {
		Feature f = (Feature)it.next();
		if (!map.adjustValue(f.getIndex(), -f.getValue()))
		    map.put(f.getIndex(), -f.getValue());
	    }
	} else {
	    while (it.hasNext()) {
		Feature f = (Feature)it.next();
		if (!map.adjustValue(f.getIndex(), f.getValue()))
		    map.put(f.getIndex(), f.getValue());
	    }
	}
    }


    public final String toString() {
	StringBuilder sb = new StringBuilder();
	toString(sb);
	return sb.toString();
    }

    private final void toString(StringBuilder sb) {
	if (null != subfv1) {
	    subfv1.toString(sb);

	    if (null != subfv2)
		subfv2.toString(sb);
	}
	ListIterator it = listIterator();
	while (it.hasNext())
	    sb.append(it.next().toString()).append(' ');
    }

}*/

