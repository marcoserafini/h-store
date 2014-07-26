package org.qcri.PartitioningPlanner.placement;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.qcri.PartitioningPlanner.placement.Plan;
import org.voltdb.CatalogContext;


public class GreedyExtendedPlacement extends Placement {
	
	Long coldPartitionWidth = 100000L; // redistribute cold tuples in chunks of 100000
	
	public GreedyExtendedPlacement(){
		
	}
	
	
	// hotTuples: tupleId --> access count
	// siteLoads: partitionId --> total access count
	public Plan computePlan(ArrayList<Map<Long, Long>> hotTuplesList, Map<Integer, Long> partitionTotals, String planFilename, int partitionCount, int timeLimit, CatalogContext catalogContext){
		
		Integer dstPartition = -1;
		Long totalAccesses = 0L;
		Long meanAccesses;
		Long hotTupleCount = 0L;
		
		Plan aPlan = new Plan(planFilename);

		for(int i = 0; i < partitionCount; ++i) {
		    if(partitionTotals.get(i) == null) {
			partitionTotals.put(i, 0L);
		    }
		}
		
		// copy partitionTotals into oldLoad
		Map<Integer, Long> oldLoad = new HashMap<Integer, Long> ();
		for(Integer i : partitionTotals.keySet()) {
			totalAccesses += partitionTotals.get(i);
			oldLoad.put(i,  partitionTotals.get(i));
		}
		
		// copy aPlan into oldPlan
		Plan oldPlan = new Plan();
		Map<Integer, List<Plan.Range>> ranges = aPlan.getAllRanges();
		for(Integer i : ranges.keySet()) {
			List<Plan.Range> partitionRanges = ranges.get(i);
			oldPlan.addPartition(i);
			for(Plan.Range range : partitionRanges) {
				oldPlan.addRange(i, range.from, range.to);
			}
		}
		
		// calculate the load and plan if the hot tuples were removed, store
		// them in oldLoad and oldPlan
		Integer partitionId = 0;
		for(Map<Long, Long>  hotTuples : hotTuplesList) {
			for(Long i : hotTuples.keySet()) {
				oldLoad.put(partitionId, oldLoad.get(partitionId) - hotTuples.get(i));
				oldPlan.removeTupleId(partitionId, i);
			}
			++partitionId;
		}
		
		// copy hot tuples list
		ArrayList<Map<Long, Long>> hotTuplesListCopy = new ArrayList<Map<Long, Long>>();
		hotTuplesListCopy.addAll(hotTuplesList);
		
		meanAccesses = totalAccesses / partitionCount;

		System.out.println("Mean access count: " + meanAccesses);
		
		for(Map<Long, Long> hotTuples : hotTuplesList) {
			hotTupleCount = hotTupleCount + hotTuples.size();
		}

		System.out.println("Received " + hotTupleCount + " hot tuples.");
		
		for(Long i = 0L; i < hotTupleCount; ++i) {
			getHottestTuple(hotTuplesList);
			//System.out.println("Processing hot tuple id " + _hotTupleId + " with access count " + _hotAccessCount);

			if(partitionTotals.get(_srcPartition) > meanAccesses || _srcPartition >= partitionCount) {
					dstPartition = getMostUnderloadedPartitionId(partitionTotals, partitionCount);
					if(dstPartition != _srcPartition) {
					        //System.out.println(" sending it to " + dstPartition);
						partitionTotals.put(_srcPartition, partitionTotals.get(_srcPartition)  - _hotAccessCount);
						partitionTotals.put(dstPartition,partitionTotals.get(dstPartition)  + _hotAccessCount);
						aPlan.removeTupleId(_srcPartition, _hotTupleId);
						if(!aPlan.hasPartition(dstPartition)) {
							aPlan.addPartition(dstPartition);
						}
						aPlan.addRange(dstPartition, _hotTupleId, _hotTupleId);
					}
				}
			hotTuplesList.get(_srcPartition).remove(_hotTupleId);

		}
		
		// place the cold tuples from the overloaded or deleted partitions
		for(Integer i : oldPlan.getAllRanges().keySet()) { // foreach partition
			if(partitionTotals.get(i) > meanAccesses || i.intValue() >= partitionCount) { 
				List<List<Plan.Range>> partitionSlices = oldPlan.getRangeSlices(i,  coldPartitionWidth);
				if(partitionSlices.size() > 0) {
					Double tupleWeight = ((double) oldLoad.get(i)) / oldPlan.getTupleCount(i); // per tuple

					for(List<Plan.Range> slice : partitionSlices) {  // for each slice
						for(Plan.Range r : slice) { 
							Integer newWeight = (int) (tupleWeight *  ((double) Plan.getRangeWidth(r)));
							dstPartition = getMostUnderloadedPartitionId(partitionTotals, partitionCount);
							
							if((partitionTotals.get(i) > meanAccesses || i.intValue() >= partitionCount) && i != dstPartition) { 		
								
								List<Plan.Range> oldRanges = aPlan.getRangeValues(i, r.from, r.to);
								for(Plan.Range oldRange : oldRanges) {
									aPlan.removeRange(i, oldRange.from);
									if(!aPlan.hasPartition(dstPartition)) {
									    aPlan.addPartition(dstPartition);
                                                                        }
                                                                        aPlan.addRange(dstPartition, Math.max(oldRange.from, r.from), Math.min(oldRange.to, r.to));

									if(oldRange.from < r.from) {
										aPlan.addRange(i, oldRange.from, r.from - 1);
									}
									if(r.to < oldRange.to) {
										aPlan.addRange(i, r.to + 1, oldRange.to);
									}
								}
								
								partitionTotals.put(i, partitionTotals.get(i) - newWeight);
								partitionTotals.put(dstPartition, partitionTotals.get(dstPartition) + newWeight);
							}
						}
					} // end for each slice
				}
			} 
		} // end for each partition

		if(!catalogContext.jarPath.getName().contains("tpcc")) {
			aPlan = demoteTuples(hotTuplesListCopy, aPlan);
		}
		removeEmptyPartitions(aPlan);
		return aPlan;
		
	}
	

}
