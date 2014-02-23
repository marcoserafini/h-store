package org.qcri.PartitioningPlanner.placement;

import org.gnu.glpk.GLPK;
import org.gnu.glpk.glp_prob;
import org.gnu.glpk.glp_iocp;
import org.gnu.glpk.SWIGTYPE_p_int;
import org.gnu.glpk.SWIGTYPE_p_double;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.qcri.PartitioningPlanner.placement.Plan;


public class OneTieredPlacement extends Placement {

	Long coldPartitionWidth = 100000L; // redistribute cold tuples in chunks of 100000
	ArrayList<Long> accesses = null; 
	ArrayList<Integer> locations = null; 
	ArrayList<List<Plan.Range>> slices = null;
	ArrayList<Long> sliceSizes = null;
	int sliceCount = 0;
	Long totalAccesses = 0L;

	public OneTieredPlacement(){
		accesses = new ArrayList<Long>(); 
		locations = new ArrayList<Integer>(); 
		slices = new ArrayList<List<Plan.Range>>();
		sliceSizes = new ArrayList<Long>();
	}

	// initialize the private data members based on the input parameters
	private void init(ArrayList<Map<Long, Long>> hotTuplesList, Map<Integer, Long> partitionTotals, Plan aPlan) {
		// ignore hotTuplesList

	        accesses = new ArrayList<Long>();
		locations = new ArrayList<Integer>();
		slices = new ArrayList<List<Plan.Range>>();
		sliceSizes = new ArrayList<Long>();
		
		// count total accesses
		totalAccesses = 0L;
		for(Integer i : partitionTotals.keySet()) {
			totalAccesses += partitionTotals.get(i);	
		}

		// store the ranges, sizes, access counts, and locations of each of the slices of cold tuples
		sliceCount = 0;
		for(Integer i : aPlan.getAllRanges().keySet()) { // for each partition
			List<List<Plan.Range>> partitionSlices = aPlan.getRangeSlices(i,  coldPartitionWidth);
			if(partitionSlices.size() > 0) {
				sliceCount += partitionSlices.size();
				Double tupleWeight = ((double) partitionTotals.get(i)) / aPlan.getTupleCount(i); // per tuple
				for(List<Plan.Range> slice : partitionSlices) {  // for each slice
					Long sliceSize = Plan.getRangeListWidth(slice);
					Long newWeight = (long) (tupleWeight *  ((double) sliceSize));
					slices.add(slice);
					sliceSizes.add(sliceSize);
					accesses.add(newWeight);
					locations.add(i);
				} // end for each slice
			}
		} // end for each partition
	}

	// hotTuples: tupleId --> access count
	// siteLoads: partitionId --> total access count
	public Plan computePlan(ArrayList<Map<Long, Long>> hotTuplesList, Map<Integer, Long> partitionTotals, String planFile, int partitionCount, int timeLimit){
		// ignore hotTuplesList
		
		Plan aPlan = new Plan(planFile);
		this.init(hotTuplesList, partitionTotals, aPlan);

		glp_prob lp = GLPK.glp_create_prob();

		GLPK.glp_set_prob_name(lp, "min_bandwidth");
		GLPK.glp_set_obj_dir(lp, GLPK.GLP_MIN);

		SWIGTYPE_p_int idxX = GLPK.new_intArray(sliceCount * partitionCount * 2 + 1);
		SWIGTYPE_p_int idxY = GLPK.new_intArray(sliceCount * partitionCount * 2 + 1);
		SWIGTYPE_p_double idxR = GLPK.new_doubleArray(sliceCount * partitionCount * 2 + 1);

		glp_iocp parm = new glp_iocp();
		GLPK.glp_init_iocp(parm);
		parm.setPresolve(GLPK.GLP_ON);
		parm.setTm_lim(timeLimit); 

		// reserved for glpk use in C++
		GLPK.intArray_setitem(idxX, 0, 0);
		GLPK.intArray_setitem(idxY, 0, 0);
		GLPK.doubleArray_setitem(idxR, 0, 0);

		Long meanAccesses = totalAccesses / partitionCount;
		System.out.println("Mean access count: " + meanAccesses);

		Long maxAccess = 0L;
		for(Long access : accesses) {
			if(access > maxAccess) {
				maxAccess = access;
			}
		}
		double partitionUpperBound = Math.max(meanAccesses, maxAccess) * 1.05; // slightly over target

		// one constraint for each partition for load balancing, one for each placement s.t. it appears exactly once
		GLPK.glp_add_rows(lp, partitionCount + sliceCount);

		// load balancing constraints
		int i;
		for(i = 1; i < partitionCount+1; ++i) {
			GLPK.glp_set_row_bnds(lp, i, GLPK.GLP_UP, 0.0, partitionUpperBound);
		}

		// each placement must appear exactly once
		for(int j = 0; j < sliceCount; ++j) {
			GLPK.glp_set_row_bnds(lp, i, GLPK.GLP_FX, 1.0, 1.0); // each placement must appear exactly once
			++i;
		}

		GLPK.glp_add_cols(lp, sliceCount * partitionCount); // binary decision variable

		// d.v. x_{j, i} -  placement is inner dimension
		int k = 1;

		// set up bandwidth costs - objective function coeffs
		long cost;
		for(int j = 0; j < partitionCount; ++j) {
			for(i = 0; i < sliceCount; ++i) {
				GLPK.glp_set_col_kind(lp, k, GLPK.GLP_BV); // _IV

				// we are moving slices of cold tuples 
				cost = ((locations.get(i) == j) ? 0 : sliceSizes.get(i)); 
				
				GLPK.glp_set_obj_coef(lp, k, cost);
				++k;
			}
		}

		// set up load balancing constraints
		int c = 1; // array position in idxR
		int d = 1; // array position in idxX and idxY
		for(int j = 1; j < partitionCount+1; ++j) {
			// list of tuple accesses are our coefficients
			for(Long access : accesses) {
				GLPK.doubleArray_setitem(idxR, c, access.doubleValue());
				++c;
			}

			for(i = 1; i < sliceCount+1; ++i) {
				// a[j, i] = c_{i-1}
				GLPK.intArray_setitem(idxX, d, j);
				GLPK.intArray_setitem(idxY, d, i + (j - 1) * sliceCount); // it is one long vector of decision variables
				++d;
			}
		}

		// load balancing constraints are correct
		k = partitionCount + 1; // first idx
		// each tuple must appear exactly once
		for(i = 1; i < sliceCount + 1; ++i) {
			for(int j = 0; j < partitionCount; ++j) {
				//i.e. a[k+1, 1] = 1
				GLPK.intArray_setitem(idxX, d, k);
				GLPK.intArray_setitem(idxY, d, j * sliceCount + i);
				GLPK.doubleArray_setitem(idxR, c, 1);
				++c;
				++d;
			}
			++k;
		}

		System.out.println("Initialized " + (d - 1) + " constraint variables, have " + GLPK.glp_get_num_bin(lp) + " binary cols.");
		GLPK.glp_load_matrix(lp, d-1, idxX, idxY, idxR);
		GLPK.glp_intopt(lp, parm);

		System.out.println("Solution cost " + GLPK.glp_mip_obj_val(lp));

		k = 1;
		for(int j = 0; j < partitionCount; ++j) {
			for(i = 0; i < sliceCount; ++i) {
				if(GLPK.glp_mip_col_val(lp, k) != 0) {
					Integer srcPartition = locations.get(i);
					Integer dstPartition = j;
					if(srcPartition != dstPartition) {
						List<Plan.Range> slice = slices.get(i);
						for(Plan.Range r : slice) { 
							if(!aPlan.hasPartition(dstPartition)) {
								aPlan.addPartition(dstPartition);
							}
							
							List<Plan.Range> oldRanges = aPlan.getRangeValues(srcPartition, r.from, r.to);
							for(Plan.Range oldRange : oldRanges) {
								aPlan.removeRange(srcPartition, oldRange.from);
								aPlan.addRange(dstPartition, Math.max(oldRange.from, r.from), Math.min(oldRange.to, r.to));

								if(oldRange.from < r.from) {
									aPlan.addRange(srcPartition, oldRange.from, r.from - 1);
								}
								if(r.to < oldRange.to) {
									aPlan.addRange(srcPartition, r.to + 1, oldRange.to);
								}
							}
						}
						
					}
				}
				++k;
			}
		}

		GLPK.glp_delete_prob(lp);
		removeEmptyPartitions(aPlan);
		return aPlan;

	}

}
