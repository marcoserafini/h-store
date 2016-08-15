package org.qcri.affinityplanner;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import org.voltdb.CatalogContext;

import it.unimi.dsi.fastutil.ints.AbstractIntComparator;
import it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.AbstractLongComparator;
import it.unimi.dsi.fastutil.longs.Long2DoubleMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

public class GreedyExtended implements Partitioner {

    // tuple -> weight
    protected static final Long2DoubleMap m_hotTuples = new Long2DoubleOpenHashMap ();

    // partition -> tuple
    protected static final LongSet[] m_partitionToHotTuples = new LongOpenHashSet [Controller.MAX_PARTITIONS];

    // vertex -> full name
    protected static Long2ObjectMap<String> m_tupleToName = new Long2ObjectOpenHashMap <String> ();

    private static long[] m_intervalsInSecs;
    private static PlanHandler m_plan_handler = null;

    public GreedyExtended (CatalogContext catalogContext, File planFile, Path[] logFiles, Path[] intervalFiles) {

        try {
            m_plan_handler = new PlanHandler(planFile, catalogContext);
        } catch (Exception e) {
            Controller.record("Could not create plan handler " + Controller.stackTraceToString(e));
            throw e;
        }

        for (int part = 0; part < Controller.MAX_PARTITIONS; part ++){
            m_partitionToHotTuples[part] = new LongOpenHashSet();
        }
        
        try {
            loadLogFile(logFiles, intervalFiles);
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            System.exit(0);
        }
        
        System.out.println("Recorded: " + m_tupleToName.size() + " vertices");

    }

    private void loadLogFile(Path[] logFiles, Path[] intervalFiles) throws Exception{

        // read monitoring intervals for all sites - in seconds
        m_intervalsInSecs = new long[intervalFiles.length];
        int currInterval = 0;
        for (Path intervalFile : intervalFiles){
            // SETUP
            BufferedReader reader;

            if (intervalFile == null){
                Controller.record("interval file name is null");
                throw new Exception();
            }
            try {
                reader = Files.newBufferedReader(intervalFile, Charset.forName("US-ASCII"));
                String line = reader.readLine();
                reader.close();
                m_intervalsInSecs[currInterval] = Long.parseLong(line) / 1000;
                currInterval++;
            } catch (IOException e) {
                Controller.record("Error while reading interval file " + intervalFile.toString() + "\n Stack trace:\n" + Controller.stackTraceToString(e));
                Thread.sleep(1000);
                throw e;
            }
            catch (NumberFormatException e1){
                Controller.record("Error while converting interval from file " + intervalFile.toString() + "\n Stack trace:\n" + Controller.stackTraceToString(e1));
                Thread.sleep(1000);
                throw e1;
            }
        }

        long currId = 1;
        Object2LongMap<String> nameToId = new Object2LongOpenHashMap<String>();

        int nextLogFile = 0;
        for(Path logFile : logFiles){
            System.out.println("Loading from file " + logFile);

            double increment = 1.0/m_intervalsInSecs[nextLogFile];
            ++nextLogFile;

            // SETUP
            BufferedReader reader;
            try {
                reader = Files.newBufferedReader(logFile, Charset.forName("US-ASCII"));
            } catch (IOException e) {
                Controller.record("Error while reading file " + logFile.toString() + "\n Stack trace:\n" + Controller.stackTraceToString(e));
                throw e;
            }

            String line;
            // vertices with number of SQL statements they are involved in
            // READ FIRST LINE
            try {
                line = reader.readLine();
            } catch (IOException e) {
                Controller.record("Error while reading file " + logFile.toString() + "\n Stack trace:\n" + Controller.stackTraceToString(e));
                throw e;
            }
            if (line == null){
                Controller.record("File " + logFile.toString() + " is empty");
                throw new Exception();
            }

            // PROCESS LINE
            while(line != null){

                String[] fields = line.split(";");

                if (!fields[0].equals("END")){
                    
                    String tupleName = fields[1];
                    
                    long id = nameToId.getLong(tupleName);
                    if (id == nameToId.defaultReturnValue()){
                        id = currId;
                        currId++;
                        nameToId.put(tupleName, id);
                        m_tupleToName.put(id, tupleName);
                    }
                    
                    double currWeight = m_hotTuples.get(id);
                    if(currWeight == m_hotTuples.defaultReturnValue()){
                        m_hotTuples.put(id, increment);
                    }
                    else{
                        m_hotTuples.put(id, currWeight + increment);
                    }
                    int partition = m_plan_handler.getPartition(fields[1]);
                    LongSet tuples = m_partitionToHotTuples[partition];
                    if (tuples == null){
                        tuples = new LongOpenHashSet();
                        m_partitionToHotTuples[partition] = tuples;
                    }
                    tuples.add(id);
                }
                try {
                    line = reader.readLine();
                } catch (IOException e) {
                    Controller.record("Error while reading file " + logFile.toString() + "\n Stack trace:\n" + Controller.stackTraceToString(e));
                    throw e;
                }
            }
        }
    }

    @Override
    public boolean repartition(boolean firstRepartition){

        int addedPartitions = 0;

        IntArrayList activePartitions = new IntArrayList();
        IntArrayList overloadedPartitions = new IntArrayList();
        
        double[] partitionLoads = new double [Controller.MAX_PARTITIONS];
        
        System.out.println("Load per partition before reconfiguration");
        for(int i = 0; i < Controller.MAX_PARTITIONS; i++){
            if(m_plan_handler.isNotEmpty(i)){
//                System.out.println(i + " is active");
                activePartitions.add(i);
                double load =  getLoadPerPartition(i);
                System.out.println(load);
                partitionLoads[i] = load;
                if (load > Controller.MAX_LOAD_PER_PART){
                    overloadedPartitions.add(i);
                }
            }
            else{
                partitionLoads[i] = 0;
            }
        }
        
        System.out.println("Total number of hot tuples:" + m_hotTuples.size());
        
        for (int fromPartition : overloadedPartitions){

            int numMovedVertices = 0;

            System.out.println("Offloading partition " + fromPartition);
            
            // loops over multiple added partitions
            while(partitionLoads[fromPartition] > Controller.MAX_LOAD_PER_PART){

                LongSet fromHotTuples = m_partitionToHotTuples[fromPartition];
                
                LongList fromHotTuplesSortedCopy = new LongArrayList();
                fromHotTuplesSortedCopy.addAll(fromHotTuples);
    
                System.out.println("Processing hot tuples ");
    
                // sort determines an _ascending_ order
                // then offload starting from end of the list in order to facilitate removal
                Collections.sort(fromHotTuplesSortedCopy, new AbstractLongComparator (){
                    @Override
                    public int compare(long o1, long o2) {
                        if (m_hotTuples.get(o1) < m_hotTuples.get(o2)){
                            return -1;
                        }
                        else if (m_hotTuples.get(o1) > m_hotTuples.get(o2)){
                            return 1;
                        }
                        return 0;
                    }
                });
    
//                System.out.println("Move hot tuples");
    
                // MOVE HOT TUPLES
    
                int topK = 1;

                int toPartition = getLeastLoadedPartition(activePartitions, partitionLoads);                
                LongSet toHotTuples = m_partitionToHotTuples[toPartition];
                
                // loops over multiple hot tuples
                while(partitionLoads[fromPartition] > Controller.MAX_LOAD_PER_PART 
                        && !fromHotTuplesSortedCopy.isEmpty() && topK <= Math.min(Controller.TOPK, fromHotTuplesSortedCopy.size())){
                    
                    long currHotTuple = fromHotTuplesSortedCopy.getLong(fromHotTuplesSortedCopy.size() - topK);
                    double currHotTupleWeight = m_hotTuples.get(currHotTuple);
                        
//                    System.out.println("Considering hot tuple " + m_tupleToName.get(currHotTuple));
//                    
                    System.out.println("\nTuple name " + m_tupleToName.get(currHotTuple) + ", id " + currHotTuple + ", and weight " + m_hotTuples.get(currHotTuple));
//    
//                    System.out.println("Load of " + fromPartition + " is " + partitionLoads[fromPartition] + " or " + getLoadPerPartition(fromPartition));
//                    System.out.println("Load of " + toPartition + " is " + partitionLoads[toPartition] + " or " + getLoadPerPartition(toPartition));

//                    System.out.println("Load " + getLoadPerPartition(overloadedPartition));
    
                    if (partitionLoads[toPartition] + currHotTupleWeight > Controller.MAX_LOAD_PER_PART){

                        System.out.println("Not moving to " + toPartition);
                        
//                        System.out.println("Load of " + toPartition + " is " + partitionLoads[toPartition] + " or " + getLoadPerPartition(toPartition));
                        
                        // get a different toPartition, this one is overloaded
                        int newToPartition = getLeastLoadedPartition(activePartitions, partitionLoads);
                        
                       if (newToPartition != toPartition){
                           
                           System.out.println("Need to change destination partition to " + newToPartition);

                            // retry the same hot tuple with the new partition
                            toPartition = newToPartition;
                            toHotTuples = m_partitionToHotTuples[toPartition];
                        }
                        else{
                            if(activePartitions.size() <= Controller.MAX_PARTITIONS 
                                    && addedPartitions <= Controller.MAX_PARTITIONS_ADDED){
            
                                System.out.println("Adding partitions");
                                // We fill up low-order partitions first to minimize the number of servers
                                addedPartitions++;
                                for(int i = 0; i < Controller.MAX_PARTITIONS; i++){
                                    if(!activePartitions.contains(i)){
                                        activePartitions.add(i);
                                        m_plan_handler.addPartitionAllTables(i);
                                        System.out.println("Added partition " + i);
                                        break;
                                    }
                                }
                            }
                            else{
                                System.out.println("Cannot add new partition to offload " + overloadedPartitions);
                                System.out.println("Moving cold tuples");
                                
                                System.out.println(activePartitions.size() );
                                System.out.println(addedPartitions);
                                
                                break;
                            }      
                        }
                        
                    }
                    else{

                        topK ++;

                        // actually move tuples in plan
    
                        ++numMovedVertices;
                        
                        System.out.println("Tuple " + m_tupleToName.get(currHotTuple) + " with code " + currHotTuple + " moved from " + fromPartition + " to " + toPartition);
                        
                        String movedVertexName = m_tupleToName.get(currHotTuple);
    
                        fromHotTuples.remove(currHotTuple);
                        toHotTuples.add(currHotTuple);

                        partitionLoads[fromPartition] -= currHotTupleWeight;
                        partitionLoads[toPartition] += currHotTupleWeight;

                        String [] fields = movedVertexName.split(",");
                        //            System.out.println("table: " + fields[0] + " from partition: " + fromPartition + " to partition " + toPartition);
                        //            System.out.println("remove ID: " + fields[1]);
                        
                        if(Controller.ROOT_TABLE == null){
                            boolean res;
                            res = m_plan_handler.removeTupleId(fields[0], fromPartition, Long.parseLong(fields[1]));
                            if (!res){
                                System.out.println("Could not remove tuple");
                            }
                            m_plan_handler.addRange(fields[0], toPartition, Long.parseLong(fields[1]), Long.parseLong(fields[1]));
                            if (!res){
                                System.out.println("Could not add tuple");
                            }
                        }
                        else{
                            m_plan_handler.removeTupleIdAllTables(fromPartition, Long.parseLong(fields[1]));
                            m_plan_handler.addRangeAllTables(toPartition, Long.parseLong(fields[1]), Long.parseLong(fields[1]));
                        }
//                        System.out.println("Current plan\n " + m_plan_handler);
//                        System.exit(0);
//                        System.out.println("Load of " + fromPartition + " is " + partitionLoads[fromPartition] + " or " + getLoadPerPartition(fromPartition));
//                        System.out.println("Load of " + toPartition + " is " + partitionLoads[toPartition] + " or " + getLoadPerPartition(toPartition));
                    }
    
                } // while(getLoadPerPartition(overloadedPartition) > Controller.MAX_LOAD_PER_PART && !fromHotTuples.isEmpty() && topK > 0)
    
//                System.out.println("Current load for partition " + fromPartition + " " + partitionLoads[fromPartition]  + " or " + getLoadPerPartition(fromPartition));
//                System.out.println("Current plan\n " + m_plan_handler);
    
                // MOVE COLD CHUNKS
    
                if(partitionLoads[fromPartition] > Controller.MAX_LOAD_PER_PART ){
                    System.out.println("\nMove cold chunks for partition " + fromPartition + "\n");

                    numMovedVertices = moveColdChunks(fromPartition, fromHotTuplesSortedCopy, activePartitions, numMovedVertices, partitionLoads);

//                    System.out.println("Debug: " + activePartitions.size());

                    System.out.println("Current load " + partitionLoads[fromPartition]);
//                    System.out.println("Current plan\n " + m_plan_handler);
                }

                // ADD NEW PARTITIONS if needed
                if (partitionLoads[fromPartition] > Controller.MAX_LOAD_PER_PART){
                    
                    System.out.println("Adding partitions for partition " + fromPartition );
                    
                    if(activePartitions.size() <= Controller.MAX_PARTITIONS 
                            && addedPartitions <= Controller.MAX_PARTITIONS_ADDED){
    
                        // We fill up low-order partitions first to minimize the number of servers
                        addedPartitions++;
                        for(int i = 0; i < Controller.MAX_PARTITIONS; i++){
                            if(!activePartitions.contains(i)){
                                activePartitions.add(i);
                                m_plan_handler.addPartitionAllTables(i);
                                System.out.println("Added partition " + i);
                                break;
                            }
                        }
                    }
                    else{
                        System.out.println("Cannot add new partition to offload " + overloadedPartitions);
                        
                        return false;
                    }
    
                }
                
            } // while(getLoadPerPartition(overloadedPartition) > Controller.MAX_LOAD_PER_PART)
        } // for (int overloadedPartition : overloadedPartitions)

        return true;
    }

    public double getLoadPerPartition(int partition){
        
        if(m_partitionToHotTuples.length <= partition){
            return 0;
        }
        
        double load = 0;

        for (long tuple : m_partitionToHotTuples[partition]){
            load += m_hotTuples.get(tuple);
        }

        long countColdTuples = 0;
        for(String table : m_plan_handler.table_names){

            List<Plan.Range> partitionRanges = m_plan_handler.getAllRanges(table, partition);
            if(partitionRanges != null && partitionRanges.size() > 0) {

                for(Plan.Range r : partitionRanges) {
                    countColdTuples += r.to - r.from + 1;
                }
            }
        }

        countColdTuples -= m_partitionToHotTuples[partition].size();
        
        double coldIncrement = 1.0 / m_intervalsInSecs[partition] / Controller.COLD_TUPLE_FRACTION_ACCESSES;
        load += countColdTuples * coldIncrement;

        return load;
    }

    public int getLeastLoadedPartition(IntList activePartitions, double[] partitonLoads){
        double minLoad = Double.MAX_VALUE;
        int res = 0;
        for (int part : activePartitions){
            double newLoad = partitonLoads[part];
            if (newLoad < minLoad){
                res = part;
                minLoad = newLoad; 
            }
        }
        return res;
    }

//    public int getLeastLoadedPartition(IntList activePartitions){
//        double minLoad = Double.MAX_VALUE;
//        int res = 0;
//        for (int part : activePartitions){
//            double newLoad = getLoadPerPartition(part);
//            if (newLoad < minLoad){
//                res = part;
//                minLoad = newLoad; 
//            }
//        }
//        return res;
//    }

    @Override
    public void writePlan(String plan_out) {
        m_plan_handler.toJSON(plan_out);
    }

    private int moveColdChunks(int fromPartition, LongList fromHotTuplesSortedCopy, IntList activePartitions, int numMovedVertices, double[] partitionLoads){

        // clone plan to allow modifications while iterating on the clone
        PlanHandler oldPlan = m_plan_handler.clone();
        
        // remove hot tuples from cold chunks
        int topk = 1;
        
        while (topk <= Math.min(Controller.TOPK, fromHotTuplesSortedCopy.size())){
            long hotTuple = fromHotTuplesSortedCopy.getLong(fromHotTuplesSortedCopy.size() - topk);
            topk++;

//            System.out.println("Hot tuple: " + m_tupleToName.get(hotTuple));
            String[] fields  = m_tupleToName.get(hotTuple).split(",");
            String table = fields[0];
            long tupleId = Long.parseLong(fields[1]);
            
            if(Controller.ROOT_TABLE == null){
                oldPlan.removeTupleId(table, fromPartition, tupleId);
            }
            else{
                oldPlan.removeTupleIdAllTables(fromPartition, tupleId);
            }
        }
        
        // consider weight of warm tuples
        Object2DoubleMap<Plan.Range> rangeToWarmTupleCost = new Object2DoubleOpenHashMap<Plan.Range>(fromHotTuplesSortedCopy.size());
        
        double coldIncrement = 1.0 / m_intervalsInSecs[fromPartition] / Controller.COLD_TUPLE_FRACTION_ACCESSES;

        while (topk <= fromHotTuplesSortedCopy.size()){
            long warmTuple = fromHotTuplesSortedCopy.getLong(fromHotTuplesSortedCopy.size() - topk);
            String[] fields  = m_tupleToName.get(warmTuple).split(",");
            String table = fields[0];
            Long tupleId = Long.parseLong(fields[1]);

            Plan.Range warmTupleRange = m_plan_handler.getRangeValue(table, fromPartition, tupleId);
            double warmTupleCost = m_hotTuples.get(warmTuple) - coldIncrement;

            double currRangeCost = rangeToWarmTupleCost.getDouble(warmTupleRange);
            if (currRangeCost == rangeToWarmTupleCost.defaultReturnValue()){
                currRangeCost = 0;
            }
            currRangeCost += warmTupleCost;
            
            rangeToWarmTupleCost.put(warmTupleRange, currRangeCost);
            
            topk++;
        }

//        System.out.println("Cloned plan without hot tuples:\n" + oldPlan);
        
        if(Controller.ROOT_TABLE == null){
            for(String table : m_plan_handler.table_names){
                numMovedVertices += moveColdChunkTable(table, oldPlan, fromPartition, activePartitions, 
                        numMovedVertices, partitionLoads, rangeToWarmTupleCost);
            }
        }
        else{
            numMovedVertices += moveColdChunkTable(Controller.ROOT_TABLE, oldPlan, fromPartition, activePartitions, 
                    numMovedVertices, partitionLoads, rangeToWarmTupleCost);
        }
        
        return numMovedVertices;
    }
    
    private int moveColdChunkTable(String table, Plan oldPlan, int fromPartition, IntList activePartitions, 
            int numMovedVertices, double[] partitionLoads, Object2DoubleMap<Plan.Range> rangeToWarmTupleCost){
        System.out.println("\nTable " + table);

        double coldIncrement = 1.0 / m_intervalsInSecs[fromPartition] / Controller.COLD_TUPLE_FRACTION_ACCESSES;

        List<List<Plan.Range>> partitionChunks = oldPlan.getRangeChunks(table, fromPartition,  (long) Controller.COLD_CHUNK_SIZE);
        if(partitionChunks.size() > 0) {

            for(List<Plan.Range> chunk : partitionChunks) {  // a chunk can consist of multiple ranges if hot tuples are taken away

//                System.out.println("\nNew cold chunk for table " + table);

                for(Plan.Range r : chunk) { 
                    
                    // see if I can stop
                    if(partitionLoads[fromPartition] <= Controller.MAX_LOAD_PER_PART){
                        return numMovedVertices;
                    }

                    System.out.println("Range " + r.from + " " + r.to);
                    
                    double warmTuplesCost = rangeToWarmTupleCost.getDouble(r);
                    if(warmTuplesCost == rangeToWarmTupleCost.defaultReturnValue()){
                        warmTuplesCost = 0;
                    }
                    double rangeWeight = Plan.getRangeWidth(r) * coldIncrement + warmTuplesCost;
                    
                    int toPartition = getLeastLoadedPartition(activePartitions, partitionLoads);     

                    System.out.println("Weight " + rangeWeight);
                    System.out.println("To partition " + toPartition);

                    if (rangeWeight + partitionLoads[toPartition] <= Controller.MAX_LOAD_PER_PART
                           && numMovedVertices + Plan.getRangeWidth(r) <= Controller.MAX_MOVED_TUPLES_PER_PART){

                        // do the move
                        if(table.equals("parts") && r.from <= 33498004 && r.to >= 33498004){
                            System.out.println("Going to move range " + r.from + "-" + r.to + " from partition " + fromPartition + " to partition " + toPartition);
                            System.out.println("Current plan " + m_plan_handler);
                        }

                        
                        numMovedVertices += Plan.getRangeWidth(r);

//                        System.out.println("Moving!");
//                        System.out.println("Load before - from partition " + fromPartition + " is " + getLoadPerPartition(fromPartition));
//                        System.out.println("Load before - to partition " + toPartition + " is " + getLoadPerPartition(toPartition));
                        
                        if(Controller.ROOT_TABLE == null){
                            m_plan_handler.moveColdRange(table, r, fromPartition, toPartition);
                        }
                        else{
                            m_plan_handler.moveColdRangeAllTables(r, fromPartition, toPartition);                                    
                        }
                        
                        partitionLoads[fromPartition] -= rangeWeight;
                        partitionLoads[toPartition] += rangeWeight;

                        if(table.equals("parts") && r.from <= 33498004 && r.to >= 33498004){
                            System.out.println("New plan " + m_plan_handler);
                        }

//                        System.out.println("Load after - from partition " + fromPartition + " is " + getLoadPerPartition(fromPartition));
//                        System.out.println("Load after - to partition " + toPartition + " is " + getLoadPerPartition(toPartition));
//                        System.out.println("New plan\n" + m_plan_handler);

                    }
                    else{
                        System.out.println("Cannot offload partition " + fromPartition);
                        
                        System.out.println(rangeWeight + partitionLoads[toPartition]);
                        System.out.println(numMovedVertices + Plan.getRangeWidth(r));
                        
                        if(numMovedVertices + Plan.getRangeWidth(r) > Controller.MAX_MOVED_TUPLES_PER_PART){
                            System.out.println("Moved too many tuples. Exiting.");
                            try {
                                Thread.sleep(5000);
                            } catch (InterruptedException e) {
                                // TODO Auto-generated catch block
                                e.printStackTrace();
                            }
                            System.exit(0);
                        }

                        return numMovedVertices;
                    }
                }
            }
        }
        return numMovedVertices;
    }
}
