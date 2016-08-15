package org.qcri.affinityplanner;

import it.unimi.dsi.fastutil.ints.AbstractIntComparator;
import it.unimi.dsi.fastutil.ints.Int2DoubleMap;
import it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;

import org.apache.log4j.Logger;

/**
 * 
 * Generic partitioner. The only abstract methods are relative to the semantic of the affinity graph.
 * It has two children classes implementing these methods:
 * 
 * GraphPartitioner considers affinity graphs where edges (and weights) are tuple - tuple.
 * It also has specific methods for moving more than one tuple together based on tuple - tuple affinity.
 * 
 * SimplePartitioner considers affinity graphs where edges (and weights) are tuple - partition.
 * It only moves one tuple at a time
 * 
 * @author mserafini
 *
 */
public abstract class PartitionerAffinity implements Partitioner {
    
    public class Move {
        public int toPartition;
        public double sndDelta;
        public double rcvDelta;
        public double rcvLoad;
        public IntOpenHashSet movingVertices;
        public boolean wasExtended;
        public int nextHotTuplePos;
        public int startingHotTuple;
        
        public Move (){
            this.toPartition = -1;
            this.sndDelta = Double.MAX_VALUE;
            this.rcvDelta = Double.MAX_VALUE;
            this.rcvLoad = -1;
            this.movingVertices = new IntOpenHashSet();
            this.wasExtended = false;
            this.nextHotTuplePos = -1;
            this.startingHotTuple = -1;
        }

        public Move(Move other) {
            this.toPartition = other.toPartition;
            this.sndDelta = other.sndDelta;
            this.rcvDelta = other.rcvDelta;
            this.rcvLoad = other.rcvLoad;
            this.movingVertices = other.movingVertices.clone();
            this.wasExtended = other.wasExtended;
            this.nextHotTuplePos = other.nextHotTuplePos;
            this.startingHotTuple = other.startingHotTuple;
        }
        
        public Move clone(){
            return new Move (this);
        }
        
        public void clearExceptMovingVertices(){
            this.toPartition = -1;
            this.sndDelta = Double.MAX_VALUE;
            this.rcvDelta = Double.MAX_VALUE;
            this.wasExtended = false;
            this.nextHotTuplePos = -1;
        }
    }
    
    private static final Logger LOG = Logger.getLogger(PartitionerAffinity.class);

    protected AffinityGraph m_graph;
    
    // refreshed every time findBestPartition is invoked
    protected double[] partitionLoadCache = new double [Controller.MAX_PARTITIONS];

    @Override
    public abstract boolean repartition (boolean firstRepartition);


    /**
     * Tries to move movingVertices from overloadedPartition to toPartition. 
     * 
     * Fails if the move does not result in a minimal gain threshold for the fromPartition OR
     *      if the toPartition becomes overloaded as an effect of the transfer.
     * 
     * If the move is allowed, it updates the graph and the plan
     * 
     * @param movingVertices
     * @param senderPartition
     * @param toPartition
     * @return number of partitions actually moved
     */
    protected int tryMoveVertices(IntSet movingVertices, int senderPartition, int toPartition) {

        int numMovedVertices = 0;
        
        double senderDelta = m_graph.getSenderDelta(movingVertices, senderPartition, toPartition);
        double receiverDelta = m_graph.getReceiverDelta(movingVertices, toPartition);

        // check that I get enough overall gain and the additional load of the receiving site does not make it overloaded
        if(senderDelta <= Controller.MIN_SENDER_GAIN_MOVE * -1
                && (receiverDelta <= 0 
                        || m_graph.getLoadPerPartition(toPartition) + receiverDelta < Controller.MAX_LOAD_PER_PART)){

            m_graph.moveHotVertices(movingVertices, toPartition);

            numMovedVertices = movingVertices.size();

            // DEBUG
            ////System.out.println("Moved " + numMovedVertices + " vertices from partition " 
//                    + senderPartition + " to partition " + toPartition + " for a global gain of " 
//                    + getGlobalDelta(movingVertices, toPartition) + ", a sender gain of " 
//                    + senderDelta + " and a receiver gain of " + receiverDelta);
        }
        return numMovedVertices;
    }
    
    /**
     * Finds the partition where the movingVertices can be moved while maximizing sender gain.
     * Looks for a move that does not overload the receiver. If this is not available, return the best we can find.
     * 
     * Output:
     * - Modifies the move argument
     * 
     */
    protected void findBestPartition(Move move, int senderPartition, IntList activePartitions){
        
        move.toPartition = -1;
        move.sndDelta = Double.MAX_VALUE;
        move.rcvDelta = Double.MAX_VALUE;
        move.rcvLoad = -1;
        boolean feasible = false;

        double currLoad = Double.MAX_VALUE;
        
        IntList localPartitions = PlanHandler.getPartitionsSite(PlanHandler.getSitePartition(senderPartition));
        
        double senderDeltaLocal = m_graph.getSenderDelta(move.movingVertices, senderPartition, true);

        partitionLoadCache[senderPartition] = Double.MAX_VALUE;

        for(int toPartition : localPartitions){
                        
            if(senderPartition == toPartition || !activePartitions.contains(toPartition)){
                continue;
            }
            
            ////System.out.println("Examining moving to partition: " + toPartition);

            double receiverDelta = m_graph.getReceiverDelta(move.movingVertices, toPartition);
            double receiverLoad = m_graph.getLoadPerPartition(toPartition);
            
            partitionLoadCache[toPartition] = receiverLoad;
            
            ////System.out.println("Receiver delta: " + receiverDelta + " min delta " + move.rcvDelta);

            if(receiverLoad + receiverDelta >= Controller.MAX_LOAD_PER_PART 
                    && receiverDelta > 0){
            
                // unfeasible move
                if (feasible){
                    ////System.out.println("Would become overloaded, but have feasible move, skipping");
                    // consider next partition
                    continue;
                }
                ////System.out.println("Would become overloaded, accepting as unfeasible");
            }
            else{
                // clear any existing unfeasible move
                if (!feasible){
                    move.toPartition = -1;
                    move.sndDelta = Double.MAX_VALUE;
                    move.rcvDelta = Double.MAX_VALUE;
                    move.rcvLoad = -1;
                }
               feasible = true;
            }

            if (receiverDelta <= move.rcvDelta){

                if (receiverDelta == move.rcvDelta){
                    ////System.out.println("Load: " + receiverLoad + " min load " + currLoad);
                    if (receiverLoad < currLoad){
                        currLoad = receiverLoad;
                        
                        ////System.out.println("Partition " + toPartition + " selected!");
                        move.toPartition = toPartition;
                        move.sndDelta = senderDeltaLocal;
                        move.rcvDelta = receiverDelta;
                        move.rcvLoad = receiverLoad;
                    }
                }
                else{
                    currLoad = receiverLoad;
                    
                    ////System.out.println("Partition " + toPartition + " selected!");
                    move.toPartition = toPartition;
                    move.sndDelta = senderDeltaLocal;
                    move.rcvDelta = receiverDelta;
                    move.rcvLoad = receiverLoad;
                }
            }
        }

        // then try to offload to remote partitions
        double senderDeltaRemote = m_graph.getSenderDelta(move.movingVertices, senderPartition, false);

        for(int toPartition : activePartitions){

            if(!localPartitions.contains(toPartition)){

                ////System.out.println("Examining moving to partition: " + toPartition);

                double receiverDelta = m_graph.getReceiverDelta(move.movingVertices, toPartition);
                double receiverLoad = m_graph.getLoadPerPartition(toPartition);
                
                partitionLoadCache[toPartition] = receiverLoad;
                
                if(receiverLoad + receiverDelta >= Controller.MAX_LOAD_PER_PART
                        && receiverDelta > 0){

                    // unfeasible move
                    if (feasible){
                        ////System.out.println("Would become overloaded, but have feasible move, skipping");
                        continue;
                    }
                    ////System.out.println("Would become overloaded, accepting as unfeasible");
                }
                else{
                    // clear any existing unfeasible move
                    if (!feasible){
                        move.toPartition = -1;
                        move.sndDelta = Double.MAX_VALUE;
                        move.rcvDelta = Double.MAX_VALUE;
                        move.rcvLoad = -1;
                    }
                    feasible = true;
                }

                ////System.out.println("Receiver delta: " + receiverDelta + " min delta " + move.rcvDelta);
                if (receiverDelta <= (move.rcvDelta * (1 - Controller.PENALTY_REMOTE_MOVE))){

                    if (receiverDelta == move.rcvDelta){
                        ////System.out.println("Load: " + receiverLoad + " min load " + currLoad);
                        if (receiverLoad < currLoad){
                            currLoad = receiverLoad;
                            
                            ////System.out.println("Selected!");
                            move.toPartition = toPartition;
                            move.sndDelta = senderDeltaRemote;
                            move.rcvDelta = receiverDelta;
                            move.rcvLoad = receiverLoad;
                        }
                    }
                    else {
                        currLoad = receiverLoad;
                        
                        ////System.out.println("Selected!");
                        move.toPartition = toPartition;
                        move.sndDelta = senderDeltaRemote;
                        move.rcvDelta = receiverDelta;
                        move.rcvLoad = receiverLoad;
                    }
                }
            }
        }
    }
    
//    protected double getDeltaMove(IntSet movingVertices, int fromPartition, int toPartition) {
//
//        double senderDelta = getSenderDelta(movingVertices, fromPartition, toPartition);
//        double receiverDelta = getReceiverDelta(movingVertices, fromPartition, toPartition);
//        
//        ////System.out.println("ReceiverDelta " + receiverDelta);
//        ////System.out.println("Load at receiver " + getLoadPerPartition(toPartition));
//
//        if(receiverDelta < 0 
//                || getLoadPerPartition(toPartition) + receiverDelta < MAX_LOAD_PER_PART){   // if gainToSite is negative, the load of the receiving site grows
//            return senderDelta;
//        }
//        
//        return Double.MAX_VALUE;
//    }
    
    /**
     * Returns a list of lists of vertices - one list for every remote partition
     * Each list includes up to k elements with the highest outside attraction to that partition
     * The list is sorted by attraction in a descending order
     * 
     * @param this_partition
     * @param k
     * @return
     */
    protected List<IntList> getBorderVertices (int this_partition, int k){

        k = Math.min(k, AffinityGraph.m_partitionVertices.get(this_partition).size());

        List<IntList> res = new ArrayList<IntList>(Controller.MAX_PARTITIONS);

        for (int i = 0; i < Controller.MAX_PARTITIONS; i++){
            res.add(new IntArrayList (k));
        }

        // maps vertices in any top k for any partition to its array of attractions
        final Int2ObjectMap<double[]> topk_attractions = new Int2ObjectOpenHashMap<double[]> ();

        int[] lowest_attraction_position = new int[Controller.MAX_PARTITIONS];
        double[] lowest_attraction = new double[Controller.MAX_PARTITIONS];


        for(int from_vertex : AffinityGraph.m_partitionVertices.get(this_partition)){

            // compute attractions
            double[] curr_attractions = new double[Controller.MAX_PARTITIONS];

            Int2DoubleMap adjacency = AffinityGraph.m_edges.get(from_vertex);
            if (adjacency != null){
                
                updateAttractions(adjacency, curr_attractions);
                
                // rank for each partition
                for(int otherPart = 0; otherPart < Controller.MAX_PARTITIONS; otherPart++){
                    
                    if(otherPart == this_partition){
                        continue;
                    }

                    // consider deltas and ignore negative attraction
                    curr_attractions[otherPart] -= curr_attractions[this_partition];
                    if (curr_attractions[otherPart] <= 0){
                        continue;
                    }
                    
                    IntList topk = res.get(otherPart);
    
                    if(topk.size() < k){
                        
                        // add to top k
                        topk.add(from_vertex);
    
                        if (curr_attractions[otherPart] < lowest_attraction[otherPart]){
                            lowest_attraction[otherPart] = curr_attractions[otherPart];
                            lowest_attraction_position[otherPart] = topk.size() - 1;
                        }

                        // update attractionMap with new attractions
                        double[] attractionMapElem = topk_attractions.get(from_vertex);
                        if (attractionMapElem == null){
                            attractionMapElem = new double[Controller.MAX_PARTITIONS];
                            topk_attractions.put(from_vertex, attractionMapElem);
                        }
                        attractionMapElem[otherPart] = curr_attractions[otherPart];
                        topk_attractions.put(from_vertex, attractionMapElem);
    
                    }
                    else{
                        if (curr_attractions[otherPart] > lowest_attraction[otherPart]){
    
                            // remove lowest vertex from attractionMap
                            int lowestVertex = topk.get(lowest_attraction_position[otherPart]);
                            double[] topk_attraction = topk_attractions.get(lowestVertex);
                            int nonZeroPos = -1;
                            for(int j = 0; j < topk_attraction.length; j++){
                                if (topk_attraction[j] != 0){
                                    nonZeroPos = j;
                                    break;
                                }
                            }
                            if (nonZeroPos == -1){
                                topk_attractions.remove(lowestVertex);
                            }
    
                            // update top k
                            topk.set(lowest_attraction_position[otherPart], from_vertex);
    
                            // add new attractions to top k attractions map
                            topk_attraction = topk_attractions.get(from_vertex);
                            if (topk_attraction == null){
                                topk_attraction = new double[Controller.MAX_PARTITIONS];
                                topk_attractions.put(from_vertex, topk_attraction);
                            }
                            topk_attraction[otherPart] = curr_attractions[otherPart];
                            topk_attractions.put(from_vertex, topk_attraction);
    
                            // recompute minimum
                            lowest_attraction[otherPart] = curr_attractions[otherPart];
                            for (int posList = 0; posList < k; posList++){
                                int vertex = topk.get(posList);
                                double attraction = topk_attractions.get(vertex)[otherPart];
                                if(attraction < lowest_attraction[otherPart]){
                                    lowest_attraction[otherPart] = attraction;
                                    lowest_attraction_position[otherPart] = posList;
                                }
                            }
                        }
                    }
                } // END for(int otherPart = 1; otherPart < MAX_PARTITIONS; otherPart++)
            } // END if (adjacency != null)
        } // END for(String from_vertex : m_graph.m_partitionVertices.get(this_partition))
       
        // sorting
        for(int otherPart = 1; otherPart < Controller.MAX_PARTITIONS; otherPart++){
            IntList topk = res.get(otherPart);

            // sort determines an _ascending_ order
            // Comparator should return "a negative integer, zero, or a positive integer as the first argument is less than, equal to, or greater than the second"
            // We want a _descending_ order, so we need to invert the comparator result

            final int part = otherPart; // make Java happy

            Collections.sort(topk, new AbstractIntComparator(){                
                @Override
                public int compare(int o1, int o2) {
                    if (topk_attractions.get(o1)[part] < topk_attractions.get(o2)[part]){
                        return 1;
                    }
                    else if (topk_attractions.get(o1)[part] > topk_attractions.get(o2)[part]){
                        return -1;
                    }
                    return 0;
                }                
            });
        }

        return res;
    }

    
    /**
     *  SCALE IN
     *  
     *  very simple policy: if a partition is underloaded, try to move its whole content to another partition
     */
    protected void scaleIn(IntList activePartitions){

        // detect underloaded partitions
        TreeSet<Integer> underloadedPartitions = new TreeSet<Integer>();
        for(int part : activePartitions){
            if (m_graph.getLoadPerPartition(part) < Controller.MIN_LOAD_PER_PART){
                underloadedPartitions.add(part);
            }
        }

        if (!underloadedPartitions.isEmpty()){
            ////System.out.println("SCALING IN");
        }


        // offload from partitions with higher id to partitions with lower id. this helps emptying up the latest servers.
        Iterator<Integer> descending = underloadedPartitions.descendingIterator();
        IntSet removedPartitions = new IntOpenHashSet();

        while(descending.hasNext()){

            int underloadedPartition = descending.next();
            ////System.out.println("Offloading partition " + underloadedPartition);
            IntOpenHashSet movingVertices = new IntOpenHashSet();
            movingVertices.addAll(AffinityGraph.m_partitionVertices.get(underloadedPartition));

            // try to offload to remote partitions
            IntList localPartitions = PlanHandler.getPartitionsSite(PlanHandler.getSitePartition(underloadedPartition));

            for(int toPartition : activePartitions){

                if(underloadedPartition == toPartition
                        || localPartitions.contains(toPartition)
                        || removedPartitions.contains(toPartition)){
                    continue;
                }

                if(!localPartitions.contains(toPartition) && !removedPartitions.contains(toPartition)){
                    double senderDelta = m_graph.getSenderDelta(movingVertices, underloadedPartition, toPartition);
                    double receiverDelta = m_graph.getReceiverDelta(movingVertices, toPartition);

                    // check that I get enough overall gain and the additional load of the receiving site does not make it overloaded
                    if(senderDelta <= Controller.MIN_SENDER_GAIN_MOVE * -1
                            && (receiverDelta <= 0
                            || m_graph.getLoadPerPartition(toPartition) + receiverDelta < Controller.MAX_LOAD_PER_PART)){

                        tryMoveVertices(movingVertices, underloadedPartition, toPartition);

                        m_graph.mergePartitions(underloadedPartition, toPartition);

                        removedPartitions.add(underloadedPartition);
                        break;
                    }
                }

//                if(!localPartitions.contains(toPartition) && !removedPartitions.contains(toPartition)){
//
//                    LOG.debug("Trying with partition " + toPartition);
//                    int movedVertices = tryMoveVertices(movingVertices, underloadedPartition, toPartition);
//
//                    if(movedVertices > 0){
//                        removedPartitions.add(underloadedPartition);
//                        break;
//                    }
//                }
            }
        }
        activePartitions.removeAll(removedPartitions);
    }

    public void writePlan(String newPlanFile){
        m_graph.planToJSON(newPlanFile);
    }
    
    public void graphToFile (Path file){
        m_graph.toFile(file);
    }

    public void graphToCPLEXFile(Path file) {
        m_graph.toCPLEXFile(file);
    }
    
    public void graphToFileMPT (Path file){
        m_graph.toFileMPT(file);
    }

    protected int moveColdChunks(int fromPartition, IntList hotVertices, IntSet warmMovedVertices, IntList activePartitions, int numMovedVertices){

        // clone plan to allow modifications while iterating on the clone
        PlanHandler oldPlan = m_graph.clonePlan();
        
        // remove hot tuples from cold chunks (these are topK hot tuples)

        for (int hotTuple : hotVertices){
//            ////System.out.println("Hot tuple: " + m_graph.getTupleName(hotTuple));
            String[] fields  = m_graph.getTupleName(hotTuple).split(",");
            String table = fields[0];
            long tupleId = Long.parseLong(fields[1]);
            
            if(Controller.ROOT_TABLE == null){
                oldPlan.removeTupleId(table, fromPartition, tupleId);
            }
            else{
                oldPlan.removeTupleIdAllTables(fromPartition, tupleId);
            }
        }

        // remove warm tuples from cold chunks (these are the other monitored tuples in the graph that were moved)

        for (int warmTuple : warmMovedVertices){
//            ////System.out.println("Warm tuple: " + m_graph.getTupleName(warmTuple));
            String[] fields  = m_graph.getTupleName(warmTuple).split(",");
            String table = fields[0];
            long tupleId = Long.parseLong(fields[1]);
            
            if(Controller.ROOT_TABLE == null){
                oldPlan.removeTupleId(table, fromPartition, tupleId);
            }
            else{
                oldPlan.removeTupleIdAllTables(fromPartition, tupleId);
            }
        }
        
        ////System.out.println("Cloned plan without hot tuples:\n" + oldPlan);
        
        // move the chunks
        
        if(Controller.ROOT_TABLE == null){
            for(String table : m_graph.getTableNames()){
                numMovedVertices += moveColdChunkTable(table, oldPlan, fromPartition, activePartitions, numMovedVertices);
            }
        }
        else{
            numMovedVertices += moveColdChunkTable(Controller.ROOT_TABLE, oldPlan, fromPartition, activePartitions, numMovedVertices);
        }
        
        return numMovedVertices;
    }
    
    private int moveColdChunkTable(String table, Plan oldPlan, int fromPartition, IntList activePartitions, int numMovedVertices){
        ////System.out.println("Table " + table);

        double coldIncrement = m_graph.getColdIncrement(fromPartition);

        List<List<Plan.Range>> partitionChunks = oldPlan.getRangeChunks(table, fromPartition,  (long) Controller.COLD_CHUNK_SIZE);
        if(partitionChunks.size() > 0) {

            for(List<Plan.Range> chunk : partitionChunks) {  // a chunk can consist of multiple ranges if hot tuples are taken away

                ////System.out.println("\nNew cold chunk of table " + table);

                for(Plan.Range r : chunk) { 

                    ////System.out.println("Range " + r.from + " " + r.to);

                    double rangeWeight = Plan.getRangeWidth(r) * coldIncrement;
                    int toPartition = m_graph.getLeastLoadedPartition(activePartitions);

                    ////System.out.println("Weight " + rangeWeight);
                    ////System.out.println("To partition " + toPartition);

                    if (rangeWeight + m_graph.getLoadPerPartition(toPartition) < Controller.MAX_LOAD_PER_PART
                           && numMovedVertices + Plan.getRangeWidth(r) < Controller.MAX_MOVED_TUPLES_PER_PART){

                        // do the move
                        
                        numMovedVertices += Plan.getRangeWidth(r);

//                        ////System.out.println("Moving!");
//                        ////System.out.println("Load before " + getLoadPerPartition(fromPartition));

                        if(Controller.ROOT_TABLE == null){
                            m_graph.moveColdRange(table, r, fromPartition, toPartition);
                        }
                        else{
                            m_graph.moveColdRangeAllTables(r, fromPartition, toPartition);                                    
                        }

//                        ////System.out.println("Load after " + getLoadPerPartition(fromPartition));
//                        ////System.out.println("New plan\n" + m_graph.planToString());

                        // after every move, see if I can stop
                        if(m_graph.getLoadPerPartition(fromPartition) <= Controller.MAX_LOAD_PER_PART){
                            return numMovedVertices;
                        }
                    }
                    else{
                        ////System.out.println("Cannot offload partition " + fromPartition);
                        return numMovedVertices;
                    }
                }
            }
        }
        return numMovedVertices;
    }

    public int numVertices(){
        return m_graph.numVertices();
    }

    public int numEdges(){
        return m_graph.numEdges();
    }

    public double getLoadPerPartition(int j){
        return m_graph.getLoadPerPartition(j);
    }

    protected abstract void updateAttractions(Int2DoubleMap adjacency, double[] attractions);

}
