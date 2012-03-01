package mstparser.old;

import mstparser.DependencyInstance;
import mstparser.FeatureVector;
import mstparser.KBestParseForest;
import mstparser.ParseForestItem;

import java.util.ArrayList;
import java.util.Arrays;
import gnu.trove.map.hash.TIntIntHashMap;

import scala.Tuple2;

public abstract class DependencyDecoder {
  public abstract mstparser.DependencyPipe pipe(); 
  protected abstract int[][] getTypes(double[][][][] nt_probs, int len);
  protected abstract boolean[][] calcChilds(int[] par);

    // static type for each edge: run time O(n^3 + Tn^2) T is number of types
    public abstract Tuple2<FeatureVector, String>[] decodeProjective(int len,
				       FeatureVector[][][] fvs,
				       double[][][] probs,
				       FeatureVector[][][][] nt_fvs,
				       double[][][][] nt_probs, int K);

    public Tuple2<FeatureVector, String>[] decodeNonProjective(int len,
					  FeatureVector[][][] fvs,
					  double[][][] probs,
					  FeatureVector[][][][] nt_fvs,
					  double[][][][] nt_probs, int K) {

	int[][] oldI = new int[len][len];
	int[][] oldO = new int[len][len];
	double[][] scoreMatrix = new double[len][len];
	double[][] orig_scoreMatrix = new double[len][len];
	boolean[] curr_nodes = new boolean[len];
	TIntIntHashMap[] reps = new TIntIntHashMap[len];

	int[][] static_types = null;
	if(this.pipe().getLabeled()) {
	    static_types = getTypes(nt_probs,len);
	}

	for(int i = 0; i < len; i++) {
	    curr_nodes[i] = true;
	    reps[i] = new TIntIntHashMap();
	    reps[i].put(i,0);
	    for(int j = 0; j < len; j++) {
		// score of edge (i,j) i --> j
		scoreMatrix[i][j] = probs[i < j ? i : j][i < j ? j : i][i < j ? 0 : 1]
		    + (this.pipe().getLabeled() ? nt_probs[i][static_types[i][j]][i < j ? 0 : 1][1]
		       + nt_probs[j][static_types[i][j]][i < j ? 0 : 1][0]
		       : 0.0);
		orig_scoreMatrix[i][j] = probs[i < j ? i : j][i < j ? j : i][i < j ? 0 : 1]
		    + (this.pipe().getLabeled() ? nt_probs[i][static_types[i][j]][i < j ? 0 : 1][1]
		       + nt_probs[j][static_types[i][j]][i < j ? 0 : 1][0]
		       : 0.0);
		oldI[i][j] = i;
		oldO[i][j] = j;
		if(i == j || j == 0) continue; // no self loops of i --> 0
	    }
	}

	TIntIntHashMap final_edges = chuLiuEdmonds(scoreMatrix,curr_nodes,oldI,oldO,false,new TIntIntHashMap(),reps);
	int[] par = new int[len];
	int[] ns = final_edges.keys();
	for(int i = 0; i < ns.length; i++) {
	    int ch = ns[i]; int pr = final_edges.get(ns[i]);
	    par[ch] = pr;
	}

	int[] n_par = getKChanges(par,orig_scoreMatrix,Math.min(K,par.length));
	int new_k = 1;
	for(int i = 0; i < n_par.length; i++)
	    if(n_par[i] > -1) new_k++;

	// Create Feature Vectors;
	int[][] fin_par = new int[new_k][len];
	FeatureVector[][] fin_fv = new FeatureVector[new_k][len];
	fin_par[0] = par;
	int c = 1;
	for(int i = 0; i < n_par.length; i++) {
	    if(n_par[i] > -1) {
		int[] t_par = new int[par.length];
		for(int j = 0; j < t_par.length; j++)
		    t_par[j] = par[j];
		t_par[i] = n_par[i];
		fin_par[c] = t_par;
		c++;
	    }
	}
	for(int k = 0; k < fin_par.length; k++) {
	    for(int i = 0; i < fin_par[k].length; i++) {
		int ch = i; int pr = fin_par[k][i];
		if(pr != -1) {
		    fin_fv[k][ch] = fvs[ch < pr ? ch : pr][ch < pr ? pr : ch][ch < pr ? 1 : 0];
		    if(this.pipe().getLabeled()) {
			fin_fv[k][ch] = 
			    fin_fv[k][ch].cat(nt_fvs[ch][static_types[pr][ch]][ch < pr ? 1 : 0][0]);
			fin_fv[k][ch] = 
			    fin_fv[k][ch].cat(nt_fvs[pr][static_types[pr][ch]][ch < pr ? 1 : 0][1]);
		    }
		}
		else
		    fin_fv[k][ch] = new FeatureVector();
	    }
	}
	
	
	FeatureVector[] fin = new FeatureVector[new_k];
	String[] result = new String[new_k];
	for(int k = 0; k < fin.length; k++) {
	    fin[k] = new FeatureVector();
	    for(int i = 1; i < fin_fv[k].length; i++)
		fin[k] = fin_fv[k][i].cat(fin[k]);
	    result[k] = "";
	    for(int i = 1; i < par.length; i++)
		result[k] += fin_par[k][i]+"|"+i + (this.pipe().getLabeled() ? ":"+static_types[fin_par[k][i]][i] : ":0") +" ";
	}
		
	// create d.
	Tuple2<FeatureVector, String>[] d = (Tuple2<FeatureVector, String>[]) new Tuple2[new_k];

	for(int k = 0; k < new_k; k++) {
	    d[k] = new Tuple2<FeatureVector, String>(fin[k], result[k].trim());
	}

	return d;
    }

    private int[] getKChanges(int[] par, double[][] scoreMatrix, int K) {
	int[] result = new int[par.length];
	int[] n_par = new int[par.length];
	double[] n_score = new double[par.length];
	for(int i = 0; i < par.length; i++) {
	    result[i] = -1;
	    n_par[i] = -1;
	    n_score[i] = Double.NEGATIVE_INFINITY;
	}

	boolean[][] isChild = calcChilds(par);

	for(int i = 1; i < n_par.length; i++) {
	    double max = Double.NEGATIVE_INFINITY;
	    int wh = -1;
	    for(int j = 0; j < n_par.length; j++) {
		if(i == j || par[i] == j || isChild[i][j]) continue;
		if(scoreMatrix[j][i] > max) { max = scoreMatrix[j][i]; wh = j; }
	    }
	    n_par[i] = wh;
	    n_score[i] = max;
	}

	for(int k = 0; k < K; k++) {
	    double max = Double.NEGATIVE_INFINITY;
	    int wh = -1;
	    int whI = -1;
	    for(int i = 0; i < n_par.length; i++) {
		if(n_par[i] == -1) continue;
		double score = scoreMatrix[n_par[i]][i];
		if(score > max) {
		    max = score; whI = i; wh = n_par[i];
		}
	    }

	    if(max == Double.NEGATIVE_INFINITY)
		break;
	    result[whI] = wh;
	    n_par[whI] = -1;
	}

	return result;
    }

    private static TIntIntHashMap chuLiuEdmonds(double[][] scoreMatrix, boolean[] curr_nodes, 
						int[][] oldI, int[][] oldO, boolean print,
						TIntIntHashMap final_edges, TIntIntHashMap[] reps) {
		
	// need to construct for each node list of nodes they represent (here only!)
		
	int[] par = new int[curr_nodes.length];
	int numWords = curr_nodes.length;
		
	// create best graph
	par[0] = -1;
	for(int i = 1; i < par.length; i++) {
	    // only interested in current nodes
	    if(!curr_nodes[i]) continue;
	    double maxScore = scoreMatrix[0][i];
	    par[i] = 0;
	    for(int j = 0; j < par.length; j++) {
		if(j == i) continue;
		if(!curr_nodes[j]) continue;
		double newScore = scoreMatrix[j][i];
		if(newScore > maxScore) {
		    maxScore = newScore;
		    par[i] = j;
		}
	    }
	}
		
	if(print) {
	    System.out.println("After init");
	    for(int i = 0; i < par.length; i++) {
		if(curr_nodes[i])
		    System.out.print(par[i] + "|" + i + " ");
	    }
	    System.out.println();
	}
		
	//Find a cycle
	ArrayList cycles = new ArrayList();
	boolean[] added = new boolean[numWords];
	for(int i = 0; i < numWords && cycles.size() == 0; i++) {
	    // if I have already considered this or
	    // This is not a valid node (i.e. has been contracted)
	    if(added[i] || !curr_nodes[i]) continue;
	    added[i] = true;
	    TIntIntHashMap cycle = new TIntIntHashMap();
	    cycle.put(i,0);
	    int l = i;
	    while(true) {
		if(par[l] == -1) {
		    added[l] = true;
		    break;
		}
		if(cycle.contains(par[l])) {
		    cycle = new TIntIntHashMap();
		    int lorg = par[l];
		    cycle.put(lorg,par[lorg]);
		    added[lorg] = true;
		    int l1 = par[lorg];
		    while(l1 != lorg) {
			cycle.put(l1,par[l1]);
			added[l1] = true;
			l1 = par[l1];
						
		    }
		    cycles.add(cycle);
		    break;
		}
		cycle.put(l,0);
		l = par[l];
		if(added[l] && !cycle.contains(l))
		    break;
		added[l] = true;
	    }
	}
		
	// get all edges and return them
	if(cycles.size() == 0) {
	    //System.out.println("TREE:");
	    for(int i = 0; i < par.length; i++) {
		if(!curr_nodes[i]) continue;
		if(par[i] != -1) {
		    int pr = oldI[par[i]][i];
		    int ch = oldO[par[i]][i];
		    final_edges.put(ch,pr);
		    //System.out.print(pr+"|"+ch + " ");
		}
		else
		    final_edges.put(0,-1);
	    }
	    //System.out.println();
	    return final_edges;
	}

	int max_cyc = 0;
	int wh_cyc = 0;
	for(int i = 0; i < cycles.size(); i++) {
	    TIntIntHashMap cycle = (TIntIntHashMap)cycles.get(i);
	    if(cycle.size() > max_cyc) { max_cyc = cycle.size(); wh_cyc = i; }
	}
		
	TIntIntHashMap cycle = (TIntIntHashMap)cycles.get(wh_cyc);
	int[] cyc_nodes = cycle.keys();
	int rep = cyc_nodes[0];
		
	if(print) {
	    System.out.println("Found Cycle");
	    for(int i = 0; i < cyc_nodes.length; i++)
		System.out.print(cyc_nodes[i] + " ");
	    System.out.println();
	}

	double cyc_weight = 0.0;
	for(int j = 0; j < cyc_nodes.length; j++) {
	    cyc_weight += scoreMatrix[par[cyc_nodes[j]]][cyc_nodes[j]];
	}
		
		
	for(int i = 0; i < numWords; i++) {
			
	    if(!curr_nodes[i] || cycle.contains(i)) continue;
			
			
	    double max1 = Double.NEGATIVE_INFINITY;
	    int wh1 = -1;
	    double max2 = Double.NEGATIVE_INFINITY;
	    int wh2 = -1;
			
	    for(int j = 0; j < cyc_nodes.length; j++) {
		int j1 = cyc_nodes[j];
				
		if(scoreMatrix[j1][i] > max1) {
		    max1 = scoreMatrix[j1][i];
		    wh1 = j1;//oldI[j1][i];
		}
				
		// cycle weight + new edge - removal of old
		double scr = cyc_weight + scoreMatrix[i][j1] - scoreMatrix[par[j1]][j1];
		if(scr > max2) {
		    max2 = scr;
		    wh2 = j1;//oldO[i][j1];
		}
	    }
			
	    scoreMatrix[rep][i] = max1;
	    oldI[rep][i] = oldI[wh1][i];//wh1;
	    oldO[rep][i] = oldO[wh1][i];//oldO[wh1][i];
	    scoreMatrix[i][rep] = max2;
	    oldO[i][rep] = oldO[i][wh2];//wh2;
	    oldI[i][rep] = oldI[i][wh2];//oldI[i][wh2];
			
	}
		
	TIntIntHashMap[] rep_cons = new TIntIntHashMap[cyc_nodes.length];
	for(int i = 0; i < cyc_nodes.length; i++) {
	    rep_cons[i] = new TIntIntHashMap();
	    int[] keys = reps[cyc_nodes[i]].keys();
	    Arrays.sort(keys);
	    if(print) System.out.print(cyc_nodes[i] + ": ");
	    for(int j = 0; j < keys.length; j++) {
		rep_cons[i].put(keys[j],0);
		if(print) System.out.print(keys[j] + " ");
	    }
	    if(print) System.out.println();
	}
		
	// don't consider not representative nodes
	// these nodes have been folded
	for(int i = 1; i < cyc_nodes.length; i++) {
	    curr_nodes[cyc_nodes[i]] = false;
	    int[] keys = reps[cyc_nodes[i]].keys();
	    for(int j = 0; j < keys.length; j++)
		reps[rep].put(keys[j],0);
	}
		
	chuLiuEdmonds(scoreMatrix,curr_nodes,oldI,oldO,print,final_edges,reps);
		
	// check each node in cycle, if one of its representatives
	// is a key in the final_edges, it is the one.
	int wh = -1;
	boolean found = false;
	for(int i = 0; i < rep_cons.length && !found; i++) {
	    int[] keys = rep_cons[i].keys();
	    for(int j = 0; j < keys.length && !found; j++) {
		if(final_edges.contains(keys[j])) {
		    wh = cyc_nodes[i];
		    found = true;
		}
	    }
	}
		
	int l = par[wh];
	while(l != wh) {
	    int ch = oldO[par[l]][l];
	    int pr = oldI[par[l]][l];
	    final_edges.put(ch,pr);
	    l = par[l];
	}
		
	if(print) {
	    int[] keys = final_edges.keys();
	    Arrays.sort(keys);
	    for(int i = 0; i < keys.length; i++)
		System.out.print(final_edges.get(keys[i])+"|"+keys[i]+" ");
	    System.out.println();
	}
		
	return final_edges;
		
    }
	
    

}
