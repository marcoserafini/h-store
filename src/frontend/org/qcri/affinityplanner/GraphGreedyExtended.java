package org.qcri.affinityplanner;

import it.unimi.dsi.fastutil.ints.Int2DoubleMap;
import it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import org.apache.log4j.Logger;
import org.qcri.affinityplanner.PartitionerAffinity.Move;
import org.voltdb.CatalogContext;

public class GraphGreedyExtended extends PartitionerAffinity {

    private static final Logger LOG = Logger.getLogger(GraphGreedyExtended.class);

    private class ExtendedAffinityGraph extends AffinityGraph {
        public ExtendedAffinityGraph (boolean tupleGranularity, CatalogContext catalogContext, File planFile,
                                      Path[] logFiles, Path[] intervalFiles, int noPartitions) throws Exception {
            super(tupleGranularity, catalogContext, planFile, logFiles, intervalFiles, noPartitions);
        }

        @Override
        public double getLoadVertices(IntSet vertices){

            double load = 0;

            int fromPartition = -1;

            for(int vertex : vertices){

                // local accesses - vertex weight
                double vertexWeight = AffinityGraph.m_vertices.get(vertex);

                if (vertexWeight == AffinityGraph.m_vertices.defaultReturnValue()){
                    LOG.debug("Cannot include external node for delta computation");
                    throw new IllegalStateException("Cannot include external node for delta computation");
                }

                load += vertexWeight;

                // remote accesses
                fromPartition = AffinityGraph.m_vertexPartition.get(vertex);
                int fromSite = PlanHandler.getSitePartition(fromPartition);

                Int2DoubleMap adjacencyList = AffinityGraph.m_edges.get(vertex);
                if(adjacencyList != null){

                    for(Int2DoubleMap.Entry edge : adjacencyList.int2DoubleEntrySet()){

                        int otherVertex = edge.getIntKey();
                        double edgeWeight = edge.getDoubleValue();

                        int otherPartition = AffinityGraph.m_vertexPartition.get(otherVertex);

                        if (otherPartition != fromPartition){

                            int otherSite = PlanHandler.getSitePartition(otherPartition);
                            double h = (fromSite == otherSite) ? Controller.LMPT_COST : Controller.DTXN_COST;
                            load += edgeWeight * h;

                        }
                    }
                }
            }


            long countColdTuples = 0;
            for(String table : m_graph.getTableNames()){

                List<Plan.Range> partitionRanges = m_graph.getAllRanges(table, fromPartition);

                if(partitionRanges.size() > 0) {

                    for(Plan.Range r: partitionRanges) {
                        countColdTuples += r.to - r.from + 1;
                        countColdTuples -= m_graph.getHotVertexCount(fromPartition);
                    }
                }
            }

            double coldIncrement = m_graph.getColdTupleWeight(fromPartition);
            load += countColdTuples * coldIncrement;

            return load;
        }

        @Override
        public double getLoadPerPartition(int partition){

            if (!AffinityGraph.isActive(partition)){
                return 0;
            }

            return getLoadVertices(AffinityGraph.m_partitionVertices.get(partition));
        }

        @Override
        public double getGlobalDelta(IntSet movingVertices, int toPartition){

            if (movingVertices == null || movingVertices.isEmpty()){
                LOG.debug("Trying to move an empty set of vertices");
                return 0;
            }

            double delta = 0;
            int toSite = (toPartition == -1) ? -1 : PlanHandler.getSitePartition(toPartition);

            for(int movingVertex : movingVertices){

                int fromPartition = AffinityGraph.m_vertexPartition.get(movingVertex);
                int fromSite = PlanHandler.getSitePartition(fromPartition);

                // if fromPartition = toPartition, there is no move so the delta is 0
                if (fromPartition != toPartition){

                    Int2DoubleOpenHashMap adjacency = AffinityGraph.m_edges.get(movingVertex);
                    if(adjacency != null){

                        for (Int2DoubleMap.Entry edge : adjacency.int2DoubleEntrySet()){

                            int adjacentVertex = edge.getIntKey();
                            double edgeWeight = edge.getDoubleValue();

                            if(!movingVertices.contains(adjacentVertex)){
                                int adjacentVertexPartition = AffinityGraph.m_vertexPartition.get(adjacentVertex);
                                int adjacentVertexSite = PlanHandler.getSitePartition(adjacentVertexPartition);

                                if (adjacentVertexPartition == fromPartition){
                                    // new MPTs from fromPartition to toPartition
                                    double k = (fromSite == toSite) ? Controller.LMPT_COST : Controller.DTXN_COST;
                                    delta += edgeWeight * k;
                                }
                                else if (adjacentVertexPartition == toPartition){
                                    // eliminating MTPs from fromPartition to toPartition
                                    double k = (fromSite == toSite) ? Controller.LMPT_COST : Controller.DTXN_COST;
                                    delta -= edgeWeight * k;
                                }
                                else{
                                    // from fromPartition -> adjacentPartition to toPartition -> adjacentPartition
                                    double h = 0;
                                    if (adjacentVertexSite == fromSite && adjacentVertexSite != toSite){
                                        // we had a local mpt, now we have a dtxn
                                        h = Controller.DTXN_COST - Controller.LMPT_COST;
                                    }
                                    else if (adjacentVertexSite != fromSite && adjacentVertexSite == toSite){
                                        // we had a dtxn, now we have a local mpt
                                        h = Controller.LMPT_COST - Controller.DTXN_COST;
                                    }
                                    delta += edgeWeight * h;
                                }
                            }
                        }
                    }
                }
            }

            return delta;
        }

        @Override
        public double getReceiverDelta(IntSet movingVertices, int toPartition){

            if (movingVertices == null || movingVertices.isEmpty()){
                LOG.debug("Trying to move an empty set of vertices");
                return 0;
            }

            double delta = 0;
            int toSite = (toPartition == -1) ? -1 : PlanHandler.getSitePartition(toPartition);

            for(int movedVertex : movingVertices){

                double vertexWeight = AffinityGraph.m_vertices.get(movedVertex);
                if (vertexWeight == AffinityGraph.m_vertices.defaultReturnValue()){
                    LOG.debug("Cannot include external node for delta computation");
                    throw new IllegalStateException("Cannot include external node for delta computation");
                }

                int fromPartition = AffinityGraph.m_vertexPartition.get(movedVertex);
                int fromSite = PlanHandler.getSitePartition(fromPartition);

                // if fromPartition == toPartition there is no movement so delta is 0
                if(fromPartition != toPartition){

                    delta += vertexWeight;

                    // compute cost of new multi-partition transactions
                    Int2DoubleOpenHashMap adjacency = AffinityGraph.m_edges.get(movedVertex);
                    if(adjacency != null){

                        for (Int2DoubleMap.Entry edge : adjacency.int2DoubleEntrySet()){

                            int adjacentVertex = edge.getIntKey();
                            double edgeWeight = edge.getDoubleValue();

                            int adjacentVertexPartition = AffinityGraph.m_vertexPartition.get(adjacentVertex);
                            int adjacentVertexSite = PlanHandler.getSitePartition(adjacentVertexPartition);

                            if (adjacentVertexPartition == toPartition){
                                // the moved vertex used to be accessed with a tuple in the destination partition
                                // the destination saves old MPTs by moving the vertex
                                double k = (fromSite == toSite) ? Controller.LMPT_COST : Controller.DTXN_COST;
                                delta -= edgeWeight * k;
                            }
                            else if (!movingVertices.contains(adjacentVertex)){
                                // the destination pays new MPTs unless the adjacent vertex is also moved here
                                if (adjacentVertexSite == toSite){
                                    delta += edgeWeight * Controller.LMPT_COST;
                                }
                                else {
                                    delta += edgeWeight * Controller.DTXN_COST;
                                }
                            }
                        }
                    }
                }
            }

            return delta;
        }

        @Override
        public double getSenderDelta(IntSet movingVertices, int senderPartition, boolean toPartitionLocal) {

            if (movingVertices == null || movingVertices.isEmpty()){
                LOG.debug("Trying to move an empty set of vertices");
                return 0;
            }

            double delta = 0;
            int senderSite = PlanHandler.getSitePartition(senderPartition);

            for(int movingVertex : movingVertices){

                double vertexWeight = AffinityGraph.m_vertices.get(movingVertex);
                if (vertexWeight == AffinityGraph.m_vertices.defaultReturnValue()){
                    LOG.debug("Cannot include external node for delta computation");
                    throw new IllegalStateException("Cannot include external node for delta computation");
                }

                int fromPartition = AffinityGraph.m_vertexPartition.get(movingVertex);

                // if fromPartition != senderPartition, there is no change for the sender so no delta
                if(fromPartition == senderPartition){

                    // lose vertex weight
                    delta -= vertexWeight;

                    // consider MPTs
                    Int2DoubleOpenHashMap adjacency = AffinityGraph.m_edges.get(movingVertex);
                    if(adjacency != null){

                        for (Int2DoubleMap.Entry edge : adjacency.int2DoubleEntrySet()){

                            int adjacentVertex = edge.getIntKey();
                            double edgeWeight = edge.getDoubleValue();

                            int adjacentVertexPartition = AffinityGraph.m_vertexPartition.get(adjacentVertex);
                            int adjacentVertexSite = PlanHandler.getSitePartition(adjacentVertexPartition);

                            if (! (adjacentVertexPartition == senderPartition && movingVertices.contains(adjacentVertex))){
                                // if the two vertices are local to the sender and are moved together, only save the vertex weight
                                // else need to consider MPT costs
                                if (adjacentVertexPartition == senderPartition) {
                                    // the sender was paying nothing, now pays the senderPartition -> toPartition MPTs
                                    // the two vertices are not moved together
                                    double k = (toPartitionLocal) ? Controller.LMPT_COST : Controller.DTXN_COST;
                                    delta += edgeWeight * k;
                                } else if (adjacentVertexSite == senderSite){
                                    // the sender was paying senderPartition -> adjacentPartition MPTs, now pays nothing
                                    delta -= edgeWeight * Controller.LMPT_COST;
                                } else {
                                    delta -= edgeWeight * Controller.DTXN_COST;
                                }
                            }
                        }
                    }
                }
            }

            return delta;
        }

    }

    //    HashSet<String> DEBUG = new HashSet<String>();
    //    boolean DEB = false;

    public GraphGreedyExtended (CatalogContext catalogContext, File planFile, Path[] logFiles, Path[] intervalFiles){

        long t1 = System.currentTimeMillis();
        try{
            m_graph = new ExtendedAffinityGraph(true, catalogContext, planFile, logFiles, intervalFiles, Controller.MAX_PARTITIONS);
        }
        catch (Exception e){
            Controller.record("Problem while loading graph. Exiting");
            return;
        }

        long t2 = System.currentTimeMillis();
        Controller.record("Time taken:" + (t2-t1));
    }

    /**
     * Repartitions the graph by using several heuristics
     * 
     * @return true if could find feasible partitioning, false otherwise
     */   
    @Override
    public boolean repartition (boolean firstRepartition) {

        if (Controller.PARTITIONS_PER_SITE == -1 || Controller.MAX_PARTITIONS == -1){
            System.out.println("GraphPartitioner: Must initialize PART_PER_SITE and MAX_PARTITIONS");
            return false;
        }

        // detect overloaded and active partitions
        IntList activePartitions = new IntArrayList(Controller.MAX_PARTITIONS);
        IntList overloadedPartitions = new IntArrayList(Controller.MAX_PARTITIONS);

        System.out.println("Load per partition before reconfiguration");
        for(int i = 0; i < Controller.MAX_PARTITIONS; i++){
            if(AffinityGraph.isActive(i)){
                activePartitions.add(i);
                double load =  m_graph.getLoadPerPartition(i);
                System.out.println(load);
                if (load > Controller.MAX_LOAD_PER_PART){
                    overloadedPartitions.add(i);
                }
            }
        }

        if (! overloadedPartitions.isEmpty()){

            /*
             *  SCALE OUT
             */

            System.out.println("Move hot tuples");

            return offloadHottestTuples(overloadedPartitions, activePartitions);
        }
        else{
            /*
             *  SCALE IN
             */

            scaleIn(activePartitions);
        }
        return true;
    }

    private boolean offloadHottestTuples(IntList overloadedPartitions, IntList activePartitions){

        int addedPartitions = 0;
        // offload each overloaded partition
        System.out.println("LOAD BALANCING");
        System.out.println("#######################");

        for(int overloadedPartition : overloadedPartitions){
            
            // DEBUG
            System.out.println("offloading site " + overloadedPartition);

            // get hottest vertices. the actual length of the array is min(Controller.MAX_MOVED_VERTICES, #tuples held site);
            int topk = Math.min(m_graph.numVertices(overloadedPartition), Controller.TOPK);
            IntList hotVerticesList = m_graph.getHottestVertices(overloadedPartition, topk);

            IntSet warmMovedVertices = new IntOpenHashSet();
            int numMovedVertices = 0;
            int nextHotTuplePos = 0;
            int lastHotVertexMoved = -1;

            int count_iter = 0;

            Move currMove = null;
            Move candidateMove = null;

            int greedyStepsAhead = Controller.GREEDY_STEPS_AHEAD;

            while(m_graph.getLoadPerPartition(overloadedPartition) > Controller.MAX_LOAD_PER_PART){

                System.out.println("Iteration " + (count_iter++));

                // Step 1) first move cold tuples, then add partition and reset if I have over-expanded movingVertices, or if I cannot expand it anymore

                if (currMove != null && 
                        (nextHotTuplePos >= hotVerticesList.size() 
                        || numMovedVertices + currMove.movingVertices.size() >= Controller.MAX_MOVED_TUPLES_PER_PART 
                        || currMove.toPartition == -1
                        || !currMove.wasExtended)){

                    // Move candidate if we can
                    
                    if(candidateMove != null 
                            && numMovedVertices + candidateMove.movingVertices.size() < Controller.MAX_MOVED_TUPLES_PER_PART){
                        // before adding a partition, move current candidate if we have one
                        System.out.println("Was almost going to add a partition");
                        System.out.println("ACTUALLY moving to " + candidateMove.toPartition 
                                + " with sender delta " + candidateMove.sndDelta + " and receiver delta " + candidateMove.rcvDelta);
                        System.out.println("Moving:\n" + m_graph.verticesToString(candidateMove.movingVertices));

                        m_graph.moveHotVertices(candidateMove.movingVertices, candidateMove.toPartition);
                        numMovedVertices += candidateMove.movingVertices.size();
                        warmMovedVertices.addAll(candidateMove.movingVertices);
                        lastHotVertexMoved = nextHotTuplePos - 1;

                        currMove = null;
                        candidateMove = null;
                        greedyStepsAhead = Controller.GREEDY_STEPS_AHEAD;

                        continue;
                    }

                    // MOVE COLD TUPLES 

                    System.out.println("Move cold tuples");

                    numMovedVertices = moveColdChunks(overloadedPartition, hotVerticesList, warmMovedVertices, activePartitions, numMovedVertices);

                    if (m_graph.getLoadPerPartition(overloadedPartition) > Controller.MAX_LOAD_PER_PART){
                        
                        // if still overloaded, add a partition

                        System.out.println("Cannot expand - Adding a new partition");

                        // We fill up low-order partitions first to minimize the number of servers
                        addedPartitions++;
                        for(int i = 0; i < Controller.MAX_PARTITIONS; i++){
                            if(!activePartitions.contains(i)){
                                activePartitions.add(i);
                                break;
                            }
                        }

                        nextHotTuplePos = lastHotVertexMoved + 1;
                        currMove = null;

                        if(activePartitions.size() > Controller.MAX_PARTITIONS 
                                || addedPartitions > Controller.MAX_PARTITIONS_ADDED){

                            System.out.println("Cannot add new partition to offload " + overloadedPartitions);
                            return false;
                        }

                        if (nextHotTuplePos >= hotVerticesList.size()){
                            System.out.println("No more hot tuples");
                            return false;
                        }
                    }
                }

                // Step 2) add one vertex to movingVertices - either expand to vertex with highest affinity or with the next hot tuple

                if (currMove == null){
                    currMove = new Move();
                }
                else {
                    System.out.println("Current load " + m_graph.getLoadPerPartition(overloadedPartition));
                    System.out.println("Current sender delta " + currMove.sndDelta);                    
                }
                
                nextHotTuplePos = expandMovingVertices (currMove, hotVerticesList, nextHotTuplePos, activePartitions, overloadedPartition);
                if (!currMove.wasExtended){
                    continue;
                }

                // Step 3) move the vertices

                System.out.println("Moving:\n" + m_graph.verticesToString(currMove.movingVertices));

                // Step 3) move the vertices

                System.out.println("Receiver: " + currMove.toPartition + ", receiver delta " + currMove.rcvDelta);

                if(currMove.toPartition != -1
                        && currMove.sndDelta <= Controller.MIN_SENDER_GAIN_MOVE * -1
                        && (m_graph.getLoadPerPartition(currMove.toPartition) + currMove.rcvDelta < Controller.MAX_LOAD_PER_PART
                                || currMove.rcvDelta <= 0)){

                    if(candidateMove == null || currMove.rcvDelta < candidateMove.rcvDelta){

                        System.out.println("CANDIDATE for moving to " + currMove.toPartition);

                        // record this move as a candidate
                        candidateMove = currMove.clone();
                        greedyStepsAhead = Controller.GREEDY_STEPS_AHEAD;
                    }
                    else{
                        // current candidate is better  
                        greedyStepsAhead--;
                    }
                }
                else if(candidateMove != null){
                    // this not a candidate but I have found one
                    greedyStepsAhead--;
                }

                // move after making enough steps
                if (greedyStepsAhead == 0){
                    System.out.println("ACTUALLY moving to " + candidateMove.toPartition + "with sender delta " 
                            + candidateMove.sndDelta + " and receiver delta " + candidateMove.rcvDelta);
                    System.out.println("Moving:\n" + m_graph.verticesToString(candidateMove.movingVertices));

                    m_graph.moveHotVertices(candidateMove.movingVertices, candidateMove.toPartition);
                    numMovedVertices += candidateMove.movingVertices.size();
                    warmMovedVertices.addAll(candidateMove.movingVertices);
                    lastHotVertexMoved = nextHotTuplePos - 1;

                    currMove = null;
                    candidateMove = null;
                    greedyStepsAhead = Controller.GREEDY_STEPS_AHEAD;
                }

            } // END while(getLoadPerSite(overloadedPartition) <= maxLoadPerSite)
        }// END for(int overloadedPartition : overloadedPartitions)
        return true;
    }

    /**
     * updates the set movingVertices with one more vertex, either the most affine to the current movingVertices 
     * or the next vertex in the verticesToMove list, depending on which one is more convenient to move out.
     * 
     * Inputs:
     * - toPartitionDelta.fst == null indicates that we don't have a predefined partition to move to
     * 
     * Outputs:
     * - returns the next position to move in verticesToMove
     * - modifies the argument movingVertices to add the new vertex
     * - modifies the argument toPartitionDelta to indicate where should the new vertex moved; (-1, inf) indicates that there is no move possible
     * 
     */
    private int expandMovingVertices (Move move, IntList hotVertices, int nextHotTuplePos, IntList activePartitions, int fromPartition){
        
        if (move.movingVertices.isEmpty()){

            // if empty, add a new hot vertex
            assert (nextHotTuplePos < hotVertices.size()); // If all hot vertices are elsewhere, I have already moved actualMaxMovedVertices so I should not be here 

            int nextHotVertex;

            do{
                nextHotVertex = hotVertices.get(nextHotTuplePos);
                nextHotTuplePos++;
            } while (nextHotTuplePos < hotVertices.size() 
                    && AffinityGraph.m_vertexPartition.get(nextHotVertex) != fromPartition);
            // the second condition is for the case where the vertex has been moved already 

            if (nextHotTuplePos == hotVertices.size()){
                move.wasExtended = false;
                return nextHotTuplePos;
            }

            move.movingVertices.add(nextHotVertex);
            move.wasExtended = true;

            findBestPartition(move, fromPartition, activePartitions);

            if(nextHotVertex == 0){
                throw new Error();
            }
        } // END if(movedVertices.isEmpty())

        else{

            // extend current moved set
            
            move.clearExceptMovingVertices();
            
            int affineVertex = getMostAffineExtension(move.movingVertices, fromPartition);

            if(affineVertex != 0){

                move.movingVertices.add(affineVertex);
                move.wasExtended = true;

                // this will populate all the fields of move
                findBestPartition(move, fromPartition, activePartitions);

                LOG.debug("Adding edge extension: " + affineVertex);
            }

            else{
                System.out.println("Could not expand");
                move.wasExtended = false;
            }
            
        } // END if(!movedVertices.isEmpty())

        // DEBUG
        LOG.debug("Moving vertices: ");
        for (int vertex : move.movingVertices){
            LOG.debug(vertex);
        }       

        return nextHotTuplePos;

    }

    /*
     * finds the vertex with the highest affinity
     */
    protected int getMostAffineExtension(IntSet vertices, int senderPartition){
        
        double maxAffinity = -1;
        int res = 0;

        for(int vertex : vertices){

            Int2DoubleMap adjacency = AffinityGraph.m_edges.get(vertex);
            if(adjacency != null){

                for(Int2DoubleMap.Entry edge : adjacency.int2DoubleEntrySet()){

                    int adjacentVertex = edge.getIntKey();
                    double affinity = edge.getDoubleValue();

                    if (affinity > maxAffinity
                            && AffinityGraph.m_vertexPartition.get(adjacentVertex) == senderPartition
                            && !vertices.contains(adjacentVertex)) {

                        maxAffinity = affinity;
                        res = adjacentVertex;
                    }
                }
            }
        }

        if (res == 0 || maxAffinity < Controller.LOCAL_AFFINITY_THRESHOLD){

            // look for affine vertices in nearby partition

            IntSet vertexSingleton = new IntOpenHashSet();

            for(int vertex : vertices){

                Int2DoubleMap adjacency = AffinityGraph.m_edges.get(vertex);
                if(adjacency != null){

                    for(Int2DoubleMap.Entry edge : adjacency.int2DoubleEntrySet()){
                        
                        int adjacentVertex = edge.getIntKey();
                        double affinity = edge.getDoubleValue();
                        
                        int newVertexPartition = AffinityGraph.m_vertexPartition.get(adjacentVertex);
                                                
                        vertexSingleton.add(adjacentVertex);
                        // setting the destination partition to be remote = worst case for the sender
                        double newVertexPartitionDelta = m_graph.getSenderDelta(vertexSingleton, newVertexPartition, false);
                        vertexSingleton.remove(adjacentVertex);
                        
                        if (affinity > maxAffinity
                                && !vertices.contains(adjacentVertex)
                                && newVertexPartition != senderPartition 
                                && newVertexPartitionDelta <= 0) {
                            maxAffinity = affinity;
                            res = adjacentVertex;
                        }
                    }
                }
            }            
        }

        return res;
    }



    @Override
    protected void updateAttractions (Int2DoubleMap adjacency, double[] attractions){
        for (int toVertex : adjacency.keySet()){

            int other_partition = AffinityGraph.m_vertexPartition.get(toVertex);
            double edge_weight = adjacency.get(toVertex);
            attractions[other_partition] += edge_weight;
        } // END for (String toVertex : adjacency.keySet())
    }

}
