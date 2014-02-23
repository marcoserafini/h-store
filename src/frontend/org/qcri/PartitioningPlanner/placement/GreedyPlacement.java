package org.qcri.PartitioningPlanner.placement;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.qcri.PartitioningPlanner.placement.Plan;


public class GreedyPlacement extends Placement {
	
	Long coldPartitionWidth = 100000L; // redistribute cold tuples in chunks of 100000 if shrinking
	
	public GreedyPlacement(){
		
	}
	
	
	// hotTuples: tupleId --> access count
	// siteLoads: partitionId --> total access count
	public Plan computePlan(ArrayList<Map<Long, Long>> hotTuplesList, Map<Integer, Long> partitionTotals, String planFilename, int partitionCount, int timeLimit){
		
		Integer dstPartition = -1;
		Long totalAccesses = 0L;
		Long meanAccesses;
		Long hotTupleCount = 0L;
		
		Plan aPlan = new Plan(planFilename);

		for(Integer i : partitionTotals.keySet()) {
			totalAccesses = totalAccesses + partitionTotals.get(i);			
		}
		for(int i = 0; i < partitionCount; ++i) {
		    if(partitionTotals.get(i) == null) {
			partitionTotals.put(i, 0L);
		    }
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

		// If we are shrinking the number of partitions, use the first fit approach 
		// to place the cold tuples from the deleted partitions
		for(Integer i : aPlan.getAllRanges().keySet()) { // foreach partition
			if(i.intValue() >= partitionCount) { // in case of shrinking number of partitions
				List<List<Plan.Range>> partitionSlices = aPlan.getRangeSlices(i,  coldPartitionWidth);
				if(partitionSlices.size() > 0) {
				        Double tupleWeight = ((double) partitionTotals.get(i)) / aPlan.getTupleCount(i); // per tuple

					for(List<Plan.Range> slice : partitionSlices) {  // for each slice

						Boolean placed = false;
						Integer newWeight = (int) (tupleWeight *  ((double) Plan.getRangeListWidth(slice)));

						for(Integer k : partitionTotals.keySet()) {
							if(partitionTotals.get(k) + newWeight <= meanAccesses) {
								dstPartition = k;
								placed = true;
								break;
							}
						}
						if(!placed) {
							dstPartition = getMostUnderloadedPartitionId(partitionTotals, partitionCount);
						}
						for(Plan.Range r : slice) { 
							if(!aPlan.hasPartition(dstPartition)) {
								aPlan.addPartition(dstPartition);
							}
							aPlan.removeRange(i, r.from);
							aPlan.addRange(dstPartition, r.from, r.to);
						}
						partitionTotals.put(dstPartition, partitionTotals.get(dstPartition) + newWeight);

					} // end for each slice
				}
			} // end in case of shrinking number of partitions
		} // end for each partition

		aPlan = demoteTuples(hotTuplesListCopy, aPlan);
		removeEmptyPartitions(aPlan);
		return aPlan;
		
	}
	

}
