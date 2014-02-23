/**
 * 
 */
package edu.brown.hashing;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.NotImplementedException;
import org.apache.log4j.Logger;
import org.voltdb.VoltType;
import org.voltdb.catalog.Table;
import org.voltdb.utils.Pair;

import edu.brown.designer.MemoryEstimator;
import edu.brown.hashing.PlannedPartitions.PartitionPhase;
import edu.brown.hashing.PlannedPartitions.PartitionRange;
import edu.brown.hashing.PlannedPartitions.PartitionedTable;
import edu.brown.hstore.reconfiguration.ReconfigurationConstants;
import edu.brown.hstore.reconfiguration.ReconfigurationUtil;

/**
 * The delta between two partition plans
 * @author aelmore
 *
 */
public class ReconfigurationPlan {

    private static final Logger LOG = Logger.getLogger(ReconfigurationPlan.class);
    Map<String,ReconfigurationTable<? extends Comparable<?>>> tables_map;
    
    //Helper map of partition ID and outgoing/incoming ranges for this reconfiguration
    protected Map<Integer, List<ReconfigurationRange<? extends Comparable<?>>>> outgoing_ranges;
    protected Map<Integer, List<ReconfigurationRange<? extends Comparable<?>>>> incoming_ranges;
    public String planDebug = "";
    
    /**
     * @throws Exception 
     * 
     */
    public ReconfigurationPlan(PartitionPhase old_phase,PartitionPhase new_phase) throws Exception {
        outgoing_ranges = new HashMap<>();
        incoming_ranges = new HashMap<>();
        assert old_phase.tables_map.keySet().equals(new_phase.tables_map.keySet()) : "Partition plans have different tables";
        tables_map = new HashMap<String, ReconfigurationPlan.ReconfigurationTable<? extends Comparable<?>>>();
        for(String table_name : old_phase.tables_map.keySet()){
            tables_map.put(table_name, new ReconfigurationTable(old_phase.getTable(table_name), new_phase.getTable(table_name)));
        }
        registerReconfigurationRanges();
        planDebug = String.format("Reconfiguration plan generated \n Out: %s \n In: %s",outgoing_ranges.toString(),incoming_ranges.toString());
        LOG.info(planDebug);
    }
    
    protected void registerReconfigurationRanges(){
        for(String table_name : tables_map.keySet()){
            for(ReconfigurationRange<?> range : tables_map.get(table_name).getReconfigurations()){
                if(outgoing_ranges.containsKey(range.old_partition)==false){
                    outgoing_ranges.put(range.old_partition, new ArrayList<ReconfigurationRange<? extends Comparable<?>>>());
                }
                if(incoming_ranges.containsKey(range.new_partition)==false){
                    incoming_ranges.put(range.new_partition, new ArrayList<ReconfigurationRange<? extends Comparable<?>>>());
                }
                outgoing_ranges.get(range.old_partition).add(range);
                incoming_ranges.get(range.new_partition).add(range);
            }
        }
    }

    public static class ReconfigurationTable<T extends Comparable<T>> {
        private List<ReconfigurationRange<T>> reconfigurations;
        String table_name;
        public ReconfigurationTable(PartitionedTable<T> old_table, PartitionedTable<T> new_table) throws Exception {
          table_name = old_table.table_name;
          setReconfigurations(new ArrayList<ReconfigurationRange<T>>());
          Iterator<PartitionRange<T>> old_ranges = old_table.partitions.iterator();
          Iterator<PartitionRange<T>> new_ranges = new_table.partitions.iterator();

          PartitionRange<T> new_range = new_ranges.next();
          T max_old_accounted_for = null;
          PartitionRange<T> old_range = null;
          // Iterate through the old partition ranges.
          // Only move to the next old rang
          while (old_ranges.hasNext() || (max_old_accounted_for != null && max_old_accounted_for.compareTo(old_range.max_exclusive)!=0) ) {
            // only move to the next element if first time, or all of the previous
            // range has been accounted for
            if (old_range == null || old_range.max_exclusive.compareTo(max_old_accounted_for) <= 0) {
              old_range = old_ranges.next();
            }

            if (max_old_accounted_for == null) {
              // We have not accounted for any range yet
              max_old_accounted_for = old_range.min_inclusive;
            }
            if (old_range.compareTo(new_range) == 0) {
              if (old_range.partition == new_range.partition) {
                // No change do nothing
              } else {
                // Same range new partition
                getReconfigurations().add(new ReconfigurationRange<T>(table_name, old_range.vt, old_range.min_inclusive, old_range.max_exclusive,
                    old_range.partition, new_range.partition));
              }
              max_old_accounted_for = old_range.max_exclusive;
              if(new_ranges.hasNext())
                  new_range = new_ranges.next();
            } else {
              if (old_range.max_exclusive.compareTo(new_range.max_exclusive) <= 0) {
                // The old range is a subset of the new range
                if (old_range.partition == new_range.partition) {
                  // Same partitions no reconfiguration needed here
                  max_old_accounted_for = old_range.max_exclusive;
                } else {
                  // Need to move the old range to new range
                  getReconfigurations().add(new ReconfigurationRange<T>(table_name, old_range.vt, max_old_accounted_for, old_range.max_exclusive,
                      old_range.partition, new_range.partition));
                  max_old_accounted_for = old_range.max_exclusive;                  
                }
		//Have we satisfied all of the new range and is there another new range to process
		if (max_old_accounted_for.compareTo(new_range.max_exclusive)==0 && new_ranges.hasNext()){
                    new_range = new_ranges.next();
		}

              } else {
                // The old range is larger than this new range
                // keep getting new ranges until old range has been satisfied
                while (old_range.max_exclusive.compareTo(new_range.max_exclusive) > 0) {
                  if (old_range.partition == new_range.partition) {
                    // No need to move this range
                    max_old_accounted_for = new_range.max_exclusive;
                  } else {
                    // move
                    getReconfigurations().add(new ReconfigurationRange<T>(table_name, old_range.vt, max_old_accounted_for, new_range.max_exclusive,
                        old_range.partition, new_range.partition));
                    max_old_accounted_for = new_range.max_exclusive;
                  }
                  if (new_ranges.hasNext() == false) {
                    throw new RuntimeException("Not all ranges accounted for");
                  }
                  new_range = new_ranges.next();
                }
              }

            }
          }
          setReconfigurations(
                  mergeReconfigurations(splitReconfigurations(getReconfigurations(),new_table.getCatalog_table()), new_table.getCatalog_table()));
        }
        
        
        private List<ReconfigurationRange<T>> mergeReconfigurations(List<ReconfigurationRange<T>> reconfiguration_range, Table catalog_table) {
            if(catalog_table==null){
                LOG.info("Catalog table is null. Not merging reconfigurations");
                return reconfiguration_range;
            }
            List<ReconfigurationRange<T>> res = new ArrayList<>();
            try{
                
                long tupleBytes = MemoryEstimator.estimateTupleSize(catalog_table);
                long currentMin = ReconfigurationConstants.MIN_TRANSFER_BYTES;
                long minRows = currentMin/tupleBytes;
                LOG.info(String.format("Trying to merge on table:%s  TupleBytes:%s  CurrentMin:%s  MinRows:%s MinTransferBytes:%s", catalog_table.fullName(),tupleBytes,currentMin,minRows, currentMin));
                
                Comparable<?> sampleKey = reconfiguration_range.get(0).getMin_inclusive() ;
                if (sampleKey instanceof Short || sampleKey instanceof Integer || sampleKey instanceof Long  ){
                	HashMap<String, ReconfigurationRange<T>> rangeMap = new HashMap<>();
                	for(ReconfigurationRange<T> range : reconfiguration_range){
                        
                		// only merge ranges that have the same old partition and same new partition
                    	int old_partition = range.old_partition;
                    	int new_partition = range.new_partition;
                    	String key = new String(old_partition + "->" + new_partition);
                    	ReconfigurationRange<T> partialRange = rangeMap.get(key);
                    	if(partialRange == null) {
                    		partialRange = new ReconfigurationRange<T>(
                                    range.table_name, range.vt, new ArrayList<Long>(), new ArrayList<Long>(), old_partition, new_partition);
                    		rangeMap.put(key, partialRange);
                    	}
                    	
                    	long max = ((Number)range.max_exclusive).longValue();
                        long min = ((Number)range.min_inclusive).longValue();
                        partialRange.getMaxList().add(max);
                        partialRange.getMinList().add(min);
                        long max_potential_keys = partialRange.getMaxPotentialKeys();
                        
                        // once we have reached the minimum number of rows, we can add this set of ranges to the output
                        if(max_potential_keys >= minRows) {
                        	int num_ranges = partialRange.getMaxList().size();
                        	if(num_ranges > 1) {
                        		LOG.info(String.format("Merging %s ranges. Table:%s",num_ranges,table_name));
                        	}
                        	
                            res.add(partialRange);
                            partialRange = null;
                            rangeMap.remove(key);
                        }
                        
                    }
                    
                	// and don't forget to add the remaining sets of ranges that didn't reach the minimum number of rows
                	for(Map.Entry<String, ReconfigurationRange<T>> rangeEntry : rangeMap.entrySet()) {
                		int num_ranges = rangeEntry.getValue().getMaxList().size();
                    	if(num_ranges > 1) {
                    		LOG.info(String.format("Merging %s ranges. Table:%s",num_ranges,table_name));
                    	}
                    	
                        res.add(rangeEntry.getValue());
                    }
                    
                } else{
                
                    throw new NotImplementedException("Can only handle types of small, long, int. Class: " +sampleKey.getClass().getName());
                }
            } catch(Exception ex){
              LOG.error("Exception splitting reconfiguration ranges, returning original list",ex);  
              return reconfiguration_range;
            }

            return res;
        }
        
        private List<ReconfigurationRange<T>> splitReconfigurations(List<ReconfigurationRange<T>> reconfiguration_range, Table catalog_table) {
            if(catalog_table==null){
                LOG.info("Catalog table is null. Not splitting reconfigurations");
                return reconfiguration_range;
            }
            boolean modified = false;
            try{
                
                long tupleBytes = MemoryEstimator.estimateTupleSize(catalog_table);
                long currentMax = ReconfigurationConstants.MAX_TRANSFER_BYTES;
                long maxRows = currentMax/tupleBytes;
                LOG.info(String.format("Trying to split on table:%s  TupleBytes:%s  CurrentMax:%s  MaxRows:%s MaxTransferBytes:%s", catalog_table.fullName(),tupleBytes,currentMax,maxRows, currentMax));
                
                List<ReconfigurationRange<T>> res = new ArrayList<>();
                Comparable<?> sampleKey = reconfiguration_range.get(0).getMin_inclusive() ;
                if (sampleKey instanceof Short || sampleKey instanceof Integer || sampleKey instanceof Long  ){
                    for(ReconfigurationRange<T> range : reconfiguration_range){
                        long max = ((Number)range.max_exclusive).longValue();
                        long min = ((Number)range.min_inclusive).longValue();
                        long max_potential_keys = max - min;
                        if (max_potential_keys > maxRows){
                            LOG.info(String.format("Splitting up a range %s-%s. Max row:%s. Table:%s",min,max,maxRows,table_name));
                            long orig_max = max;
                            Class<?> keyclass =  sampleKey.getClass();
                            long keysRemaining = max_potential_keys;

                            //We need to split up this range
                            while(keysRemaining > 0) {
                                max = Math.min(min+maxRows,orig_max);
                                LOG.info(String.format("New range %s-%s",min,max));
                                ReconfigurationRange<T> newRange = new ReconfigurationRange<T>(
                                        range.table_name, range.vt, (T)keyclass.cast(min), (T)keyclass.cast(max), range.old_partition, range.new_partition);
                                min = max;
                                keysRemaining-=maxRows;
                                modified = true;
                                res.add(newRange);
                            }
                            
                        } else {
                            //This range is ok to keep
                            res.add(range);
                        }
                        
                    }
                } else{
                
                    throw new NotImplementedException("Can only handle types of small, long, int. Class: " +sampleKey.getClass().getName());
                }
                if(modified){
                    return res;
                }
            } catch(Exception ex){
              LOG.error("Exception splitting reconfiguration ranges, returning original list",ex);  
            }

            return reconfiguration_range;
        }




        public List<ReconfigurationRange<T>> getReconfigurations() {
            return reconfigurations;
        }




        public void setReconfigurations(List<ReconfigurationRange<T>> reconfigurations) {
            this.reconfigurations = reconfigurations;
        }
      }
      
      /**
       * A partition range that holds old and new partition IDs.
       * As of 2.4.14 a range may not be non-contiguous, so a range
       * may actually hold a set of ranges. A range is the granual of 
       * migration / reconfiguration.
       * 
       * @author aelmore
       * 
       * @param <T>
       */
      public static class ReconfigurationRange<T extends Comparable<T>> extends PartitionRange<T> {
        public int old_partition;
        public int new_partition;
        private List<Pair<Long,Long>> ranges;
        private List<Long> min_list;
        private List<Long> max_list;
        
        //To reduce visibility from PartitionRange as this can have multiple ranges
        private T min_inclusive;
        private Long min_inclusive_long;
        private T max_exclusive;
        private Long max_exclusive_long;
        
        public String table_name;
        private boolean single_range = false; //single range or multi
         
        
        public ReconfigurationRange(String table_name, VoltType vt, T min_inclusive, T max_exclusive, int old_partition, int new_partition)  {
          super(vt, min_inclusive, max_exclusive);
          //FIXME change to be type generic

          this.min_inclusive = min_inclusive;
          this.max_exclusive = max_exclusive;
          min_inclusive_long = ((Number)min_inclusive).longValue();
          max_exclusive_long = ((Number)max_exclusive).longValue();
          Pair<Long,Long> minMax = new Pair<Long, Long>(min_inclusive_long, max_exclusive_long);
          ranges = new ArrayList<>();
          ranges.add(minMax);
          
          min_list = new ArrayList<>();
          min_list.add(min_inclusive_long);
          max_list = new ArrayList<>();
          max_list.add(max_exclusive_long);
          
          this.old_partition = old_partition;
          this.new_partition = new_partition;
          this.table_name = table_name;
          this.single_range = true;
        }
        
        public ReconfigurationRange(String table_name, VoltType vt, List<Long> min_inclusive, List<Long> max_exclusive, int old_partition, int new_partition) {
            super(vt);
            //FIXME change to be type generic

            ranges = new ArrayList<>();
            for(int i = 0; i < min_inclusive.size() && i < max_exclusive.size(); ++i) {
            	Pair<Long,Long> minMax = new Pair<Long, Long>(min_inclusive.get(i), max_exclusive.get(i));
                ranges.add(minMax);
            }
            
            min_list = new ArrayList<>();
            min_list.addAll(min_inclusive);
            max_list = new ArrayList<>();
            max_list.addAll(max_exclusive);
            
            this.old_partition = old_partition;
            this.new_partition = new_partition;
            this.table_name = table_name;
            this.single_range = false;
        }

        @Override
        public String toString(){
        	if(min_inclusive != null && max_exclusive != null) {
        		return String.format("ReconfigRange (%s) keys:[%s,%s) p_id:%s->%s ",table_name,min_inclusive,max_exclusive,old_partition,new_partition);
        	}
        	else {
        		String keys = "";
        		for(int i = 0; i < min_list.size() && i < max_list.size(); ++i) {
        			if(i != 0) {
        				keys += ", ";
        			}
        			keys += "[" + min_list.get(i) + "," + max_list.get(i) + ")";
        		}
        		return String.format("ReconfigRange (%s) keys:%s p_id:%s->%s ",table_name,keys,old_partition,new_partition);
        	}
        }
        
        public boolean inRange(Comparable<?> key){
            try{
                long keyL = ((Number)key).longValue();
                for(Pair<Long,Long> range : ranges) {
                    long min_long = range.getFirst();
                    long max_long = range.getSecond();
                    if(min_long <= keyL && (max_long > keyL || 
                            (max_long == min_long && min_long == keyL))){
                        return true;
                    }
                }
            } catch(Exception e){
                LOG.error("TODO only number keys supported");
                LOG.error(e);
            }
            return false;
        }
        
        @SuppressWarnings("unchecked")
        public <T> T castKey(Comparable<?> key){
            return (T)key;
        }
        
       

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + new_partition;
            result = prime * result + old_partition;
            result = prime * result + ((ranges == null) ? 0 : ranges.hashCode());
            result = prime * result + ((table_name == null) ? 0 : table_name.hashCode());
            return result;
        }
        

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            ReconfigurationRange other = (ReconfigurationRange) obj;
            if (new_partition != other.new_partition)
                return false;
            if (old_partition != other.old_partition)
                return false;
            if (ranges == null) {
                if (other.ranges != null)
                    return false;
            } else if (!ranges.equals(other.ranges))
                return false;
            if (table_name == null) {
                if (other.table_name != null)
                    return false;
            } else if (!table_name.equals(other.table_name))
                return false;
            return true;
        }

        public List<Long> getMinList() {
            return min_list;
        }

        public List<Long> getMaxList() {
            return max_list;
        }
        
        public Long getMaxPotentialKeys() {
        	Long max_potential_keys = 0L;
        	for(int i = 0; i < min_list.size() && i < max_list.size(); ++i) {
        		max_potential_keys += max_list.get(i) - min_list.get(i);
        	}
        	return max_potential_keys;
        }

        public T getMin_inclusive() {
            if(!single_range) 
                throw new RuntimeException("Trying to get min_inclusive when multiple ranges exists");
            return min_inclusive;
        }

        public T getMax_exclusive() {
            if(!single_range) 
                throw new RuntimeException("Trying to get max_exclusive when multiple ranges exists");
            return max_exclusive;
        } 
        
        public boolean isSingleRange() {
        	return single_range;
        }
    }
      

      
      
    public Map<Integer, List<ReconfigurationRange<? extends Comparable<?>>>> getOutgoing_ranges() {
        return outgoing_ranges;
    }

    public Map<Integer, List<ReconfigurationRange<? extends Comparable<?>>>> getIncoming_ranges() {
        return incoming_ranges;
    }
}
