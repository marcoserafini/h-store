package org.qcri.affinityplanner;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.log4j.Logger;
import org.json.JSONException;
import org.json.JSONObject;

import edu.brown.utils.FileUtil;

import java.util.StringTokenizer;
import java.io.BufferedWriter;
import java.io.FileWriter;

public class Plan {

    private static final Logger LOG = Logger.getLogger(Controller.class);

    public static final String PLANNED_PARTITIONS = "partition_plan";
    // the TreeMap is a range. The key is the beginning of interval, the value is the end.
    protected Map<String, HashMap<Integer, TreeMap<Long, Long>>> tableToPartitionsToRanges = new HashMap<String, HashMap<Integer, TreeMap<Long, Long>>>();
    protected String[] table_names;
    protected String m_defaultTable;
    
    public class Range{
        Long from;
        Long to;


        public String toString() {
            String rangeStr = new String();
            rangeStr = from.toString() + "-" + (to.toString() + 1);
            return rangeStr;
        }

        @Override
        public boolean equals (Object other){
            return (other instanceof Range) && (this.from == ((Range) other).from) && (this.to == ((Range) other).to);
        }
        
        @Override
        public int hashCode() {
            return (int) ((int) (from * 31) ^ to);
          }

    }

    boolean isEqual(Plan other) {
        for(String table : this.tableToPartitionsToRanges.keySet()){
            Map<Integer, TreeMap<Long, Long>> thisPartitionToRanges = this.tableToPartitionsToRanges.get(table.toLowerCase());
            Map<Integer, TreeMap<Long, Long>> otherPartitionToRanges = other.tableToPartitionsToRanges.get(table.toLowerCase());
            if(otherPartitionToRanges == null){
                return false;
            }
            for(Integer partition : thisPartitionToRanges.keySet()) {
                Map<Long, Long> myPartition = thisPartitionToRanges.get(partition);
                Map<Long, Long> otherPartition = otherPartitionToRanges.get(partition);
                if(otherPartition == null) {
                    return false;
                }
                for(Long from : myPartition.keySet()) {
                    if(myPartition.get(from) != otherPartition.get(from)) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    public Plan (String filename){
        loadFromJSON(filename);
    }
    
    public void addPartition(String table, Integer partitionId) {
        HashMap<Integer, TreeMap<Long, Long>> partitionToRanges = tableToPartitionsToRanges.get(table.toLowerCase());
        if (partitionToRanges == null){
            partitionToRanges = new HashMap<Integer, TreeMap<Long, Long>> ();
            tableToPartitionsToRanges.put(table.toLowerCase(), partitionToRanges);
        }
        if(!partitionToRanges.containsKey(partitionId)){
            TreeMap<Long, Long> emptyRange = new TreeMap<Long,Long>();
            partitionToRanges.put(partitionId, emptyRange);
        }
    }
    
    public void addPartitionAllTables(Integer partitionId){
        for (String table : table_names){
            addPartition(table, partitionId);
        }
    }

    public void removePartition(String table, Integer partitionId) {
        HashMap<Integer, TreeMap<Long, Long>> partitionToRanges = tableToPartitionsToRanges.get(table.toLowerCase());
        if (partitionToRanges != null){	    
            partitionToRanges.remove(partitionId);
        }
    }

    public Set<Integer> getAllPartitions() {
        String anyTable = tableToPartitionsToRanges.keySet().iterator().next();
        HashMap<Integer, TreeMap<Long, Long>> partitionToRanges = tableToPartitionsToRanges.get(anyTable);
        if (partitionToRanges != null){     
            return partitionToRanges.keySet();
        }
        return null;
    }

    public boolean hasPartition(Integer partitionId) {
        String anyTable = tableToPartitionsToRanges.keySet().iterator().next();
        HashMap<Integer, TreeMap<Long, Long>> partitionToRanges = tableToPartitionsToRanges.get(anyTable);
        if (partitionToRanges != null){     
            return (partitionToRanges.get(partitionId) != null);
        }
        return false;
    }

    /*public void addRange(Integer partition, Long from, Long to){

		boolean suspect = false;

		if(from != to) {
			System.out.println("Adding range " + from + "-" + to + " to " + partition);
		}
		else {
			System.out.println("Adding range " + to + " to " + partition);
		}

		if(from == 2001) {
			suspect = true;
		}


		TreeMap<Long, Long> ranges = partitionToRanges.get(partition);
		// find plans that intersect or are adjacent to the new range
		Map.Entry<Long, Long> precedingFrom = ranges.floorEntry(from - 1);
		Map.Entry<Long, Long> precedingTo = ranges.floorEntry(to + 1);

		if(suspect) {
			System.out.println("Suspect has neighbors " + precedingFrom.toString() + " and " + precedingTo.toString());
		}



		if (precedingFrom == null){
			// from is smaller than any previous range
			if (precedingTo == null){
				// no range intersecting with this range, create a new range
				ranges.put(from,to);
			}
			else{
				// merge the two ranges
				// first remove all ranges before precedingTo
				removeRange(partition, precedingTo.getKey());
				ranges.put(from, Math.max(to,precedingTo.getValue()));

			}
		}
		else{
			if(precedingTo.equals(precedingFrom) && from > precedingFrom.getValue()){
				// no range intersecting with this range, create a new range
				ranges.put(from,to);
			}
			// if rhs linked with a range, lhs untouched
			else if(from > precedingFrom.getValue() && precedingTo.getKey() < to){
				ranges.subMap(precedingFrom.getKey(),false,precedingTo.getValue(),false).clear();

				Long lowerBound = from;
				Long upperBound = Math.max(precedingTo.getValue(), to);
				ranges.put(lowerBound, upperBound);

			}
			// lhs linked w/range, rhs not
			else if(precedingFrom.getValue() > from){


			}
			// intersects on both sides
			else {
				ranges.subMap(precedingFrom.getKey(),true,precedingTo.getKey(),true).clear();
				Long lowerBound = precedingFrom.getKey();
				Long upperBound = Math.max(precedingTo.getValue(), to);
				ranges.put(lowerBound, upperBound);

			}

		} 
	} */

    /**
     * Returns false if it is impossible to add the range
     * 
     * @param table
     * @param partition
     * @param from
     * @param to
     * @return
     */
    public boolean addRange(String table, Integer partition, Long from, Long to){
        
        HashMap<Integer, TreeMap<Long, Long>> partitionToRanges = tableToPartitionsToRanges.get(table.toLowerCase());
        if (partitionToRanges == null){
            return false;
        }

        // temporarily expand bounds s.t. it merges with adjacent ranges
        Long fromTest = from - 1;
        Long toTest = to + 1;

        TreeMap<Long, Long> ranges = partitionToRanges.get(partition);		
        if(ranges == null){
            return false;
        }

        Map.Entry<Long, Long> precedingFrom = ranges.floorEntry(fromTest);
        Map.Entry<Long, Long> precedingTo = ranges.floorEntry(toTest);



        if (precedingFrom == null){
            // from is smaller than any previous range
            if (precedingTo == null){
                // no range intersecting with this range, create a new range
                ranges.put(from,to);
            }
            else{
                // merge the two ranges
                // first remove all ranges before precedingTo
                ranges.headMap(precedingTo.getKey(),true).clear();
                ranges.put(from, Math.max(to,precedingTo.getValue()));
            }
        }
        else{
            if(precedingTo.equals(precedingFrom) && fromTest > precedingFrom.getValue()){
                // no range intersecting with this range, create a new range
                ranges.put(from,to);
            }
            else {

                Long lowerBound = from;
                Long upperBound = to;

                if(precedingFrom.getValue() >= fromTest) {
                    lowerBound = Math.min(from, precedingFrom.getKey());
                }

                if(precedingTo.getKey() <= toTest) {
                    upperBound = Math.max(to, precedingTo.getValue());
                }

                // remove all ranges intersecting with (from,to)
                ranges.subMap(lowerBound,true,upperBound,true).clear();
                // merge
                ranges.put(lowerBound, upperBound);                	 
            }
        }

        return true;
    }

    public boolean addRangeAllTables(Integer partition, Long from, Long to){
        boolean res = true;
        for (String table : table_names){
            res = res && addRange(table, partition, from, to);
        }
        return res;
    }
        
    /**
     * Returns true if the element was present
     * 
     * @param table
     * @param partition
     * @param from
     * @return
     */
    public boolean removeRange(String table, Integer partition, Long from){
        HashMap<Integer, TreeMap<Long, Long>> partitionToRanges = tableToPartitionsToRanges.get(table.toLowerCase());
        if (partitionToRanges == null){
            return false;
        }

        TreeMap<Long, Long> ranges = partitionToRanges.get(partition);
        if(ranges == null){
            return false;
        }

        return (ranges.remove(from) == null);
    }

    /**
     * Returns true if the element was present
     * 
     * @param table
     * @param partition
     * @param tupleId
     * @return
     */
    public boolean removeTupleId(String table, Integer partition, Long tupleId) {
        HashMap<Integer, TreeMap<Long, Long>> partitionToRanges = tableToPartitionsToRanges.get(table.toLowerCase());
        if (partitionToRanges == null){
            return false;
        }

        Map.Entry<Long,Long> precedingRange = partitionToRanges.get(partition).floorEntry(tupleId);
        if(precedingRange == null) return false;
        if(precedingRange.getValue() > tupleId && precedingRange.getKey() < tupleId){ // split into two ranges
            //			System.out.println("Splitting up range " + precedingRange.getKey() + "-" + precedingRange.getValue() + " at " + tupleId);
            Long upperBound = precedingRange.getValue();
            Long lowerBound = precedingRange.getKey();
            partitionToRanges.get(partition).remove(lowerBound);
            partitionToRanges.get(partition).put(lowerBound, tupleId - 1);
            partitionToRanges.get(partition).put(tupleId + 1, upperBound);
        }
        // range of one
        else if(precedingRange.getKey().equals(precedingRange.getValue())) {
            partitionToRanges.get(partition).remove(tupleId);
        }
        else if(precedingRange.getValue().equals(tupleId)) {
            Long lowerBound = precedingRange.getKey();
            partitionToRanges.get(partition).remove(lowerBound);
            partitionToRanges.get(partition).put(lowerBound, tupleId - 1);

        }
        else if(precedingRange.getKey().equals(tupleId)) {
            Long upperBound = precedingRange.getValue();
            partitionToRanges.get(partition).remove(tupleId);
            partitionToRanges.get(partition).put(tupleId + 1, upperBound);
        }

        return true;
    }

    public boolean removeTupleIdAllTables(Integer partition, Long tupleId) {
        boolean res = true;
        for (String table : table_names){
            res = res && removeTupleId(table, partition, tupleId);
        }
        return res;
    }

    Integer getTuplePartition(String table, Long tupleId) {
        HashMap<Integer, TreeMap<Long, Long>> partitionToRanges = tableToPartitionsToRanges.get(table.toLowerCase());
        if (partitionToRanges == null){
            return -1;
        }

        for(Integer partition : partitionToRanges.keySet()) {
            if(getRangeValue(table, partition, tupleId) != null) {
                return partition;
            }
        }
        return -1;
    }

    public String printPartition(String table, Integer partition) {
        HashMap<Integer, TreeMap<Long, Long>> partitionToRanges = tableToPartitionsToRanges.get(table.toLowerCase());
        if (partitionToRanges == null){
            return null;
        }

        String output = new String();
        Boolean first = true;
        String rangeStr = new String();

        TreeMap<Long, Long> ranges = partitionToRanges.get(partition);
        for(Map.Entry<Long, Long> range : ranges.entrySet()){
            rangeStr = range.getKey() + "-" + (range.getValue() + 1);

            if(!first) {
                output = output + ",";
            }
            else {
                first = false;
            }

            output = output + rangeStr;
        }

        return output;

    }


    private Range  parseRange(String src) {
        Long value;
        Range parsed = new Range();

        if(src.contains("-")) {
            StringTokenizer inner = new StringTokenizer(src, "-");
            String lhs = inner.nextToken();
            String rhs = inner.nextToken();
            parsed.from = Long.parseLong(lhs);
            parsed.to = Long.parseLong(rhs);
            if(parsed.to > parsed.from) {
                parsed.to--; // assuming we are passed non-inclusive ranges
            }
        }
        else {
            value = Long.parseLong(src);
            parsed.from = parsed.to = value;
        }
        return parsed;


    }

    private TreeMap<Long, Long> parseRanges(String srcRanges) {
        TreeMap<Long, Long> ranges = new TreeMap<Long, Long>();
        StringTokenizer st = new StringTokenizer(srcRanges, ",");
        Range parsedRange;

        // just one
        if(srcRanges.contains(",") == false && srcRanges.length() > 0) {

            parsedRange = parseRange(srcRanges);
            ranges.put(parsedRange.from, parsedRange.to);
        }

        while (st.hasMoreTokens()) { 
            String rangeStr = st.nextToken();
            parsedRange = parseRange(rangeStr);
            ranges.put(parsedRange.from, parsedRange.to);
        }

        return ranges;

    }

    public void printPlan() {
        for(String table : tableToPartitionsToRanges.keySet()){
            System.out.println("Table " + table);
            Map<Integer, TreeMap<Long, Long>> partitionToRanges = tableToPartitionsToRanges.get(table.toLowerCase());
            for(Integer partition : partitionToRanges.keySet()) {
                System.out.print("Partition " + partition + ": " + printPartition(table, partition));
                System.out.println("");

            }
        }

    }





    public Range getRangeValue(String table, Integer partition, Long value){
        HashMap<Integer, TreeMap<Long, Long>> partitionToRanges = tableToPartitionsToRanges.get(table.toLowerCase());
        if (partitionToRanges == null){
            return null;
        }

        Map.Entry<Long,Long> precedingRange = partitionToRanges.get(partition).floorEntry(value);
        if(precedingRange == null) return null;
        if(precedingRange.getValue() >= value){
            Range res = new Range();
            res.from = precedingRange.getKey();
            res.to = precedingRange.getValue();
            return res;
        }
        else return null;
    }

    // get all the ranges including the given range in the given partition
    public List<Range> getRangeValues(String table, Integer partition, Long from, Long to){

        List<Range> ranges = getAllRanges(table, partition);
        if(ranges == null){
            return null;
        }

        List<Range> returnedRanges = new ArrayList<Range>();
        for(Range range : ranges) {
            if(range.from <= to && range.to >= from) {
                returnedRanges.add(range);
            }
        }
        return returnedRanges;
    }

    // get all the ranges partially or completely overlapping the given range in the given partition
    public List<Range> getRangesOverlappingValues(String table, Integer partition, Long from, Long to){

        List<Range> ranges = getAllRanges(table, partition);
        if(ranges == null){
            return null;
        }

        List<Range> returnedRanges = new ArrayList<Range>();
        for(Range range : ranges) {
            if(from <= range.from && range.from <= to 
                    || from <= range.from && range.to <= to) {
                returnedRanges.add(range);
            }
        }
        return returnedRanges;
    }

    public List<Range> getAllRanges(String table, Integer partition){
        HashMap<Integer, TreeMap<Long, Long>> partitionToRanges = tableToPartitionsToRanges.get(table.toLowerCase());
        if (partitionToRanges == null){
            return null;
        }

        List<Range> res = new ArrayList<Range>();
        TreeMap<Long, Long> ranges = partitionToRanges.get(partition);
        if(ranges == null){
            return null;
        }

        for(Map.Entry<Long, Long> range : ranges.entrySet()){
            Range tmp = new Range();
            tmp.from = range.getKey();
            tmp.to = range.getValue();
            res.add(tmp);
        }
        return res;
    }

    public Map<Integer, List<Range>> getAllRanges(String table){

        HashMap<Integer, TreeMap<Long, Long>> partitionToRanges = tableToPartitionsToRanges.get(table.toLowerCase());
        if (partitionToRanges == null){
            return null;
        }

        Map<Integer, List<Range>> res = new HashMap<Integer, List<Range>>();
        for(Integer partition: partitionToRanges.keySet()){
            res.put(partition,getAllRanges(table, partition));
        }
        return res;
    }

    public Long getTupleCount(String table, Integer partition) {

        HashMap<Integer, TreeMap<Long, Long>> partitionToRanges = tableToPartitionsToRanges.get(table.toLowerCase());
        if (partitionToRanges == null){
            return null;
        }

        TreeMap<Long, Long> ranges = partitionToRanges.get(partition);
        Long sum = 0L;
        for(Long k : ranges.keySet()) {
            sum += ranges.get(k) - k + 1; // inclusive
        }
        return sum;
    }

    static Long getRangeWidth(Range r) {
        return r.to - r.from + 1; // inclusive
    }

    static String rangeSliceToString(List<Range> slice) {
        String printed = new String();

        if(slice.size() > 0) {
            printed = slice.get(0).toString();
            for(Integer i = 1; i < slice.size(); ++i) {
                printed = printed + "," + slice.get(i).toString();
            }
        }
        return printed;
    }

    static void printRangeSlices(List<List<Range>> slices) {
        for(Integer i = 0; i < slices.size(); ++i) {
            System.out.println("Slice " + i + ": " + rangeSliceToString(slices.get(i)));
        }
    }

    static Long getRangeListWidth(List<Range> ranges) {
        Long sum = 0L;

        for(Range r : ranges) {
            sum = sum + getRangeWidth(r);
        }
        return sum;
    }

    // for algos such as the first fit, bin packer, subdivide cold tuples
    // into sets of equal size from pre-existing ranges
    List<List<Range>> getRangeChunks(String table, Integer partition, Long sliceWidth) {

        HashMap<Integer, TreeMap<Long, Long>> partitionToRanges = tableToPartitionsToRanges.get(table.toLowerCase());
        if (partitionToRanges == null){
            return null;
        }

        TreeMap<Long, Long> ranges = partitionToRanges.get(partition);
        List<List<Range>> slices = new ArrayList<List<Range>>();

        long accumulatedSum = 0L;
        List<Range> partialList = new ArrayList<Range>();
        long rangeLength = 0L;

        for(Long i : ranges.keySet()) {
            rangeLength = ranges.get(i) - i + 1; // inclusive
            if(accumulatedSum + rangeLength < sliceWidth) {
                Range tmp = new Range();
                tmp.from = i;
                tmp.to = ranges.get(i);
                partialList.add(tmp);
                accumulatedSum = accumulatedSum + rangeLength;
            }
            else if(accumulatedSum + rangeLength == sliceWidth) {
                // emit
                slices.add(partialList);
                partialList = new ArrayList<Range>();
                accumulatedSum = 0L;
            }
            // selection intersects range
            else {
                Range tmp1 = new Range();
                tmp1.from = i;
                tmp1.to = i + sliceWidth - accumulatedSum - 1; // fill in the remainder

                partialList.add(tmp1);
                slices.add(partialList);


                partialList = new ArrayList<Range>();
                accumulatedSum = 0L;

                Range tmp2 = new Range();
                tmp2.from = tmp1.to + 1;
                tmp2.to = ranges.get(i);
                // if it spans multiple slices, unwind it
                while(getRangeWidth(tmp2) > sliceWidth) {
                    tmp1 = new Range();
                    tmp1.from = tmp2.from;
                    tmp1.to = tmp2.from + sliceWidth - 1;
                    partialList.add(tmp1);

                    slices.add(partialList);

                    partialList = new ArrayList<Range>();
                    tmp2.from = tmp1.to + 1;
                }

                if(getRangeWidth(tmp2) == sliceWidth) {
                    slices.add(partialList);
                    partialList = new ArrayList<Range>();					
                }
                else {
                    accumulatedSum = getRangeWidth(tmp2);
                    partialList.add(tmp2);
                }
            }
        }

        if(!partialList.isEmpty()) {
            slices.add(partialList);
        }

        return slices;

    }

    public void toJSON (String newPlanFile)  {
        JSONObject dstData = new JSONObject();
        System.out.println("Planner writing to " + newPlanFile);

        // begin JSON block
        try {
            JSONObject jsonPlan = new JSONObject();
            JSONObject tableNameObject = new JSONObject();

            jsonPlan.put("tables", tableNameObject);
            for(String table_name : table_names) {
                JSONObject tableObject = new JSONObject();
                JSONObject partitionDelimiter = new JSONObject();

                tableNameObject.put(table_name, partitionDelimiter);
                partitionDelimiter.put("partitions", tableObject);

                Map<Integer, TreeMap<Long,Long>> partitionToRanges = tableToPartitionsToRanges.get(table_name.toLowerCase()); 
                for(Integer partition : partitionToRanges.keySet()) {
                    TreeMap<Long, Long> ranges = partitionToRanges.get(partition);
                    if (ranges != null && !ranges.isEmpty()){
                        tableObject.put(partition.toString(), printPartition(table_name, partition));
                    }
                }

            }
            dstData.put(PLANNED_PARTITIONS, jsonPlan);
            dstData.put("default_table", m_defaultTable);

        } catch(JSONException f) {
            System.out.println("Convertion of the plan into a JSONObject failed!");
            return;
        }

        try {
            BufferedWriter output = new BufferedWriter(new FileWriter(newPlanFile));    	 
            output.write(dstData.toString(2));
            output.close();
        }
        catch (Exception e) {
            System.out.println("Failed to write partitioning plan to " + newPlanFile);
            return;
        }
    }

    public String toString(){
        StringBuffer res = new StringBuffer();
        for(String table_name : table_names) {
            res.append("Table: " + table_name + "\n");

            Map<Integer, TreeMap<Long,Long>> partitionToRanges = tableToPartitionsToRanges.get(table_name.toLowerCase()); 
            for(Integer partition : partitionToRanges.keySet()) {
                res.append("Partition " + partition + ": " + printPartition(table_name, partition) + "\n");
            }
        }

        return res.toString();
    }

    private JSONObject traverseLevel(JSONObject srcData, String key) {
        try {
            JSONObject child = srcData.getJSONObject(key);
            return child;
            //srcData = child;
            //String[] keys = JSONObject.getNames(srcData);
            //System.out.println("Now srcData has " + keys.length + " keys first one is " + keys[0] );
        }
        catch (JSONException e) {
            System.out.println("Failed to recurse down JSON tree for key " + key);
            return null;
        }
    }

    private JSONObject traverseLevelSingle(JSONObject srcData) {
        String[] keys = JSONObject.getNames(srcData);
        return traverseLevel(srcData, keys[0]);
    }

    // read in the last plan
    private void loadFromJSON(String filename) {
        tableToPartitionsToRanges.clear();
        JSONObject firstLevel;
        JSONObject srcData;
        String inputData = FileUtil.readFile(filename);

        //		System.out.println("Working from " + inputData);
        try {
            firstLevel = new JSONObject(inputData);	
        } catch(JSONException e) {
            System.out.println("Failed to read in " + filename);
            return;
        }

        if(!firstLevel.has(PLANNED_PARTITIONS)) {
            return;
        }

        // traverse "partition_plan" object
        srcData = traverseLevelSingle(firstLevel);		
        // traverse "tables" object
        srcData = traverseLevelSingle(srcData);         

        table_names = JSONObject.getNames(srcData);
        for (String table : table_names){
            HashMap<Integer, TreeMap<Long,Long>> partitionToRanges = new HashMap<Integer, TreeMap<Long,Long>>();
            tableToPartitionsToRanges.put(table.toLowerCase(), partitionToRanges);

            JSONObject partitions;
            try {
                partitions = srcData.getJSONObject(table);
            } catch (JSONException e1) {
                LOG.warn("Failed to read in " + filename);
                return;
            }

            // traverse "partitions" object
            partitions = traverseLevelSingle(partitions);

            String[]  partitionKeys = JSONObject.getNames(partitions);
            for(Integer i = 0; i < partitionKeys.length; ++i) {
                Integer partitionNo = Integer.parseInt(partitionKeys[i]);
                String partitionRanges = new String();
                try {
                    partitionRanges = partitions.getString(partitionKeys[i]);
                } catch(JSONException e) {
                    System.out.println("Failed to parse a partition range " + partitionNo);
                    return;
                }
                partitionToRanges.put(partitionNo, parseRanges(partitionRanges));
            }
        }
        
        try {
            m_defaultTable = firstLevel.getString("default_table");
        } catch(JSONException e) {
            System.out.println("Failed to read in " + filename);
            return;
        }
    }

//    public boolean verifyPlan (Map<String,Integer> tableSizes){
//
//        for(String table : table_names){
//            Integer maxTuple  = tableSizes.get(table);
//            if (maxTuple == null){
//                System.out.println("Size of table " + table + " not specified for verification");
//                return false;
//            }
//            for(int t = 0; t < maxTuple; t++){
//                int foundInPart = -1;
//                Map<Integer, List<Plan.Range>> ranges = getAllRanges(table);
//                for(Integer part : ranges.keySet()) {
//                    List<Plan.Range> partitionRanges = ranges.get(part);
//                    for(Plan.Range range : partitionRanges) {
//                        if (t >= range.from && t <= range.to){
//                            if (foundInPart >= 0){
//                                System.out.println("Tuple " + t + " of table " + table + " was found in partitions " + foundInPart + " and " + part);
//                                return false;
//                            }
//                            foundInPart = -1;
//                        }
//                    }
//                }
//                if (foundInPart == -1){
//                    System.out.println("Tuple " + t + " of table " + table + " was not found");
//                    return false;
//                }
//            }
//        }
//        return true;
//    }

    public void moveColdRange(String table, Plan.Range movedRange, int fromPart, int toPart){

        List<Plan.Range> oldRanges = getRangeValues(table, fromPart, movedRange.from, movedRange.to);

        for(Plan.Range oldRange : oldRanges) {
            // remove chunk from overloaded partition
            removeRange(table, fromPart, oldRange.from);

            if(!hasPartition(toPart)) {
                addPartition(table, toPart);
            }

            // add chunk to destination partition 
            addRange(table, toPart, Math.max(oldRange.from, movedRange.from), Math.min(oldRange.to, movedRange.to));

            // add back range minus chunk to overloaded partition
            if(oldRange.from < movedRange.from) {
                addRange(table, fromPart, oldRange.from, movedRange.from - 1);
            }
            if(movedRange.to < oldRange.to) {
                addRange(table, fromPart, movedRange.to + 1, oldRange.to);
            }
        }
    }
    
    public void moveColdRangeAllTables(Plan.Range movedRange, int fromPart, int toPart){
        for (String table : table_names){
            moveColdRange(table, movedRange, fromPart, toPart);
        }
    }

    public void mergePartitions (int sourcePartition, int destinationPartition){
        for (String table : table_names){
            List<Range> sourceRanges = getAllRanges(table, sourcePartition);

            for (Plan.Range sourceRange : sourceRanges){
                removeRange(table, sourcePartition, sourceRange.from);
                addRange(table, destinationPartition, sourceRange.from, sourceRange.to);
            }
        }
    }

    
    String[] getTables(){
        return table_names;
    }

    // only for cloning
    protected Plan(){
        
    }
    
    // adapted from Becca's GreedyExtendedPlacement
    public Plan clone(){
        
        Plan cloned = new Plan ();
        
        cloned.table_names = this.table_names;
        cloned.m_defaultTable = this.m_defaultTable;
        
        cloned.tableToPartitionsToRanges = new HashMap<String, HashMap<Integer, TreeMap<Long,Long>>> ();
        
        for(String table : table_names){
            
            HashMap<Integer, TreeMap<Long,Long>> clonedPartitionToRanges = new HashMap<Integer, TreeMap<Long,Long>>();
            cloned.tableToPartitionsToRanges.put(table, clonedPartitionToRanges);

            Map<Integer, TreeMap<Long,Long>> partitionsToRanges = tableToPartitionsToRanges.get(table.toLowerCase());
            
            for (Map.Entry<Integer, TreeMap<Long,Long>> partititionToRanges :  partitionsToRanges.entrySet()){
                
                TreeMap<Long,Long> clonedRanges = new TreeMap<Long,Long>();
                clonedPartitionToRanges.put(partititionToRanges.getKey(), clonedRanges);
                
                TreeMap<Long,Long> ranges = partititionToRanges.getValue();
                for (Map.Entry<Long, Long> range : ranges.entrySet()){
                    clonedRanges.put(range.getKey(), range.getValue());
                }
            }
        }
        return cloned;
    }
}
