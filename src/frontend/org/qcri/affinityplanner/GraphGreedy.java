package org.qcri.affinityplanner;

import it.unimi.dsi.fastutil.ints.*;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;

import org.apache.log4j.Logger;
import org.voltdb.CatalogContext;

public class GraphGreedy extends PartitionerAffinity {

    private static final Logger LOG = Logger.getLogger(GraphGreedy.class);
    
    public GraphGreedy (CatalogContext catalogContext, File planFile, Path[] logFiles, Path[] intervalFiles){

        long t1 = System.currentTimeMillis();
        try{
            m_graph = new AffinityGraph(true, catalogContext, planFile, logFiles, intervalFiles, Controller.MAX_PARTITIONS);
        }
        catch (Exception e){
            Controller.record("Problem while loading graph. Exiting");
            return;
        }

        long t2 = System.currentTimeMillis();

        // DEBUG
        //        m_graph.toFile(new File("Graph.txt").toPath());
    }

    /**
     * Repartitions the graph by using several heuristics
     * 
     * @return true if could find feasible partitioning, false otherwise
     */   
    @Override
    public boolean repartition (boolean firstRepartition) {

        if (Controller.PARTITIONS_PER_SITE == -1 || Controller.MAX_PARTITIONS == -1){
            ////System.out.println("GraphPartitioner: Must initialize PART_PER_SITE and MAX_PARTITIONS");
            return false;
        }

        // detect overloaded and active partitions
        IntList activePartitions = new IntArrayList(Controller.MAX_PARTITIONS);

        for(int i = 0; i < Controller.MAX_PARTITIONS; i++){
            if(AffinityGraph.isActive(i)){
                activePartitions.add(i);
            }
        }

        // find overloaded partitions

        IntList overloadedPartitions = new IntArrayList();

        ////System.out.println("Load per partition after moving border tuples");
        for (int i : activePartitions){
            double load =  m_graph.getLoadPerPartition(i);
            partitionLoadCache[i] = load;
            ////System.out.println(load);
            if (load > Controller.MAX_LOAD_PER_PART){
                overloadedPartitions.add(i);
            }
        }

        // move back formerly hot tuples that are not hot anymore
        if(!firstRepartition){
            // TODO
            System.out.println("REVERTING");
            revertMoves(activePartitions);
            System.out.println("Reverted plan:");
            System.out.println(m_graph.planToString());
        }

        if (! overloadedPartitions.isEmpty()){

            /*
             *  SCALE OUT
             */

            System.out.println("MOVING HOT TUPLES");

            return offloadHottestTuples(overloadedPartitions, activePartitions);
        }
        else{
            /*
             *  SCALE IN
             */

            System.out.println("SCALING IN");

            scaleIn(activePartitions);
        }
        return true;
    }

    //    private void testNoOverload(int toPartition){
    //        double load =  getLoadPerPartition(toPartition);
    //        if (load > Controller.MAX_LOAD_PER_PART){
    //            ////System.out.println("Partition " + toPartition + " is overloaded");
    //            System.exit(0);
    //        }
    //    }

    private boolean offloadHottestTuples(IntList overloadedPartitions, IntList activePartitions){

        int addedPartitions = 0;
        // offload each overloaded partition
        ////System.out.println("\nLOAD BALANCING");
        ////System.out.println("#######################");
        
        ////System.out.println(Controller.DTXN_COST);

        Path movesFile = FileSystems.getDefault().getPath(".", "moves.log");
        BufferedWriter writer;

        try {
            writer = Files.newBufferedWriter(movesFile, Charset.forName("US-ASCII"), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            LOG.error("Controller failed while writing moves file " + movesFile.toString());
            System.out.println("Controller failed while writing moves file " + movesFile.toString());
            return false;
        }


        for(int overloadedPartition : overloadedPartitions){

            ////System.out.println("%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%% offloading site " + overloadedPartition + " %%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%");
            ////System.out.println("Active partitions " + activePartitions.toString());

            // get hottest vertices. the actual length of the array is min(Controller.MAX_MOVED_VERTICES, #tuples held site);
            int topk = Math.min(m_graph.numVertices(overloadedPartition), Controller.TOPK);
            IntList hotVerticesList = m_graph.getHottestVertices(overloadedPartition, topk);

            int numMovedVertices = 0;
            int nextHotTuplePos = 0;
            int lastHotVertexMoved = -1;

//            int count_iter = 0;

            Move currMove = null;
            Move candidateMove = null;
            
            int leastLoadedPartition = m_graph.getLeastLoadedPartition(activePartitions);

            int greedyStepsAhead = Controller.GREEDY_STEPS_AHEAD;
            
            while(partitionLoadCache[overloadedPartition] > Controller.MAX_LOAD_PER_PART){
                
                ////System.out.println("\n ######## ITERATION " + (count_iter++) + " ###########");

                // ######## add partition and reset if I have over-expanded movingVertices, or if I cannot expand it anymore ##########

                if (currMove != null && 
                        (nextHotTuplePos >= hotVerticesList.size() 
                        || numMovedVertices + currMove.movingVertices.size() >= Controller.MAX_MOVED_TUPLES_PER_PART 
                        || currMove.toPartition == -1
                        || !currMove.wasExtended)){

                    if(candidateMove != null ){
                        //                            && numMovedVertices + candidateMove.movingVertices.size() <= Controller.MAX_MOVED_TUPLES_PER_PART){
                        // before adding a partition, move current candidate if we have one
                        ////System.out.println("Giving up on expanding the moving set");
                        ////System.out.println("ACTUALLY moving to " + candidateMove.toPartition 
//                                + " with sender delta " + candidateMove.sndDelta + " and receiver delta " + candidateMove.rcvDelta);
                        ////System.out.println("Moving:\n" + m_graph.verticesToString(candidateMove.movingVertices));

                        m_graph.moveHotVertices(candidateMove.movingVertices, candidateMove.toPartition);
                        numMovedVertices += candidateMove.movingVertices.size();

                        writeMove(writer, candidateMove);
                        //                        lastHotVertexMoved = nextHotTuplePos - 1;

                        //                        testNoOverload(candidateMove.toPartition);

                        partitionLoadCache[overloadedPartition] = m_graph.getLoadPerPartition(overloadedPartition);
                        nextHotTuplePos = candidateMove.nextHotTuplePos;
                        lastHotVertexMoved = nextHotTuplePos - 1;
                        currMove = null;
                        candidateMove = null;
                        greedyStepsAhead = Controller.GREEDY_STEPS_AHEAD;
                        leastLoadedPartition = m_graph.getLeastLoadedPartition(activePartitions);

                        continue;
                    }
                    
                    // if none of the current partitions wants to take the group, add partitions
                    ////System.out.println("Cannot expand - Adding a new partition");

                    // We fill up low-order partitions first to minimize the number of servers
                    int newPartCount = 0;
                    for(int i = 0; i < Controller.MAX_PARTITIONS; i++){
                        if(!activePartitions.contains(i)){
                            activePartitions.add(i);
                            newPartCount++;
                            if (newPartCount >= Controller.ADDED_PARTITION_CHUNK_SIZE){
                                break;
                            }
                        }
                    }
                    addedPartitions += newPartCount;

                    if(activePartitions.size() > Controller.MAX_PARTITIONS 
                            || addedPartitions > Controller.MAX_PARTITIONS_ADDED
                            || newPartCount == 0){

                        ////System.out.println("GIVING UP!! Cannot add new partition to offload " + overloadedPartitions);

                        return false;
                    }
                        
                    // reset and restart the moving set
                    nextHotTuplePos = lastHotVertexMoved + 1;
                    currMove = null;
                    candidateMove = null;
                    greedyStepsAhead = Controller.GREEDY_STEPS_AHEAD;
                    leastLoadedPartition = m_graph.getLeastLoadedPartition(activePartitions);

                } // END if (numMovedVertices + movingVertices.size() >= Controller.MAX_MOVED_TUPLES_PER_PART || nextPosToMove >= hotVerticesList.size() || (toPart_sndDelta_glbDelta.fst != null && toPart_sndDelta_glbDelta.fst == -1))

                // ########## ELSE add one vertex to movingVertices - either expand to vertex with highest affinity or with the next hot tuple

                if (currMove == null){
                    currMove = new Move();
                }
                else {
                    ////System.out.println("Current load " + partitionLoadCache[overloadedPartition]);
                    ////System.out.println("Current sender delta " + currMove.sndDelta);                    
                }

                nextHotTuplePos = expandMovingVertices (currMove, hotVerticesList, nextHotTuplePos, activePartitions, overloadedPartition, leastLoadedPartition);
                if (!currMove.wasExtended){
                    continue;
                }

                // ########## move the vertices if could expand

                ////System.out.println("Moving:\n" + m_graph.verticesToString(currMove.movingVertices));
                ////System.out.println("Receiver: " + currMove.toPartition + ", receiver delta " + currMove.rcvDelta);

                // check move
                if(currMove.toPartition != -1
                        && currMove.sndDelta <= Controller.MIN_SENDER_GAIN_MOVE * -1){
                    
                    // this move is valid for the sender but we need to find a receiver to make it feasible 
                        
//                    if (getLoadPerPartition(currMove.toPartition) + currMove.rcvDelta > Controller.MAX_LOAD_PER_PART
//                            && currMove.rcvDelta > 0){
//                        
//                        // the receiver in the move is not good enough. see if we can find a better receiver.
//                        findBestPartition(currMove, overloadedPartition, activePartitions);
//                    }
                    
                    if (currMove.rcvLoad + currMove.rcvDelta <= Controller.MAX_LOAD_PER_PART
                            || currMove.rcvDelta <= 0){

                        if(candidateMove == null || currMove.rcvDelta <= candidateMove.rcvDelta){
    
                            ////System.out.println("CANDIDATE for moving to " + currMove.toPartition);
    
                            // record this move as a candidate
                            candidateMove = currMove.clone();
                            candidateMove.nextHotTuplePos = nextHotTuplePos;
                            greedyStepsAhead = Controller.GREEDY_STEPS_AHEAD;
                            
                            continue;
                        }
                    }

                }
                if(candidateMove != null){
                    // this not a candidate but I have found one
                    greedyStepsAhead--;
                }

                // move after making enough steps
                if (greedyStepsAhead == 0){
                    ////System.out.println("ACTUALLY moving to " + candidateMove.toPartition + " with sender delta " 
//                            + candidateMove.sndDelta + " and receiver delta " + candidateMove.rcvDelta);
                    ////System.out.println("Moving:\n" + m_graph.verticesToString(candidateMove.movingVertices));

                    m_graph.moveHotVertices(candidateMove.movingVertices, candidateMove.toPartition);
                    numMovedVertices += candidateMove.movingVertices.size();
                    //                    lastHotVertexMoved = nextHotTuplePos - 1;
                    //                    testNoOverload(candidateMove.toPartition);

                    writeMove(writer, candidateMove);

                    partitionLoadCache[overloadedPartition] = m_graph.getLoadPerPartition(overloadedPartition);
                    nextHotTuplePos = candidateMove.nextHotTuplePos;
                    lastHotVertexMoved = nextHotTuplePos - 1;
                    currMove = null;
                    candidateMove = null;
                    greedyStepsAhead = Controller.GREEDY_STEPS_AHEAD;
                    leastLoadedPartition = m_graph.getLeastLoadedPartition(activePartitions);

                }

            } // END while(getLoadPerSite(overloadedPartition) <= maxLoadPerSite)
        }// END for(int overloadedPartition : overloadedPartitions)

        try {
            writer.close();
        } catch (IOException e) {
            LOG.error("Controller failed while closing file " + writer.toString() + "\nStack trace " + Controller.stackTraceToString(e));
            System.out.println("Controller failed while closing file " + writer.toString() + "\nStack trace " + Controller.stackTraceToString(e));
        }

        return true;
    }

    /**
     * updates the set movingVertices with one more vertex, either the most affine to the current movingVertices 
     * or the next vertex in the verticesToMove list, depending on which one is more convenient to move out.
     */
    private int expandMovingVertices (Move move, IntList hotVertices, int nextHotTuplePos, IntList activePartitions, int fromPartition, int leastLoadedPartition){

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
            move.startingHotTuple = nextHotVertex;
            ////System.out.println("Adding vertex " + m_graph.m_vertexName.get(nextHotVertex));

            findBestPartition(move, fromPartition, activePartitions);

            move.wasExtended = true;

            if(nextHotVertex == 0){
                throw new Error();
            }
        } // END if(movedVertices.isEmpty())

        else{

            // extend current moved set

            int affineVertex = getMostAffineExtension(move.movingVertices, fromPartition);

            if(affineVertex != 0){

                // DEBUG
                ////System.out.println("Adding edge extension: " + AffinityGraph.m_vertexName.get(affineVertex));

                move.movingVertices.add(affineVertex);
                move.wasExtended = true;

                move.sndDelta = m_graph.getSenderDelta(move.movingVertices, fromPartition, move.toPartition);
                move.rcvDelta = m_graph.getReceiverDelta(move.movingVertices, move.toPartition);
                
                if(move.rcvLoad + move.rcvDelta <= Controller.MAX_LOAD_PER_PART || move.rcvDelta <= 0){
                    // if the move to the current partition is feasible, go ahead with it
                    return nextHotTuplePos;
                }

                // check if the move needs to change toPartition after the extension
                int extensionPartition = getMostAffinePartition(affineVertex);

                if (extensionPartition > -1 
                        && extensionPartition != move.toPartition
                        && extensionPartition != fromPartition){

                    double extensionReceiverDelta = m_graph.getReceiverDelta(move.movingVertices, extensionPartition);
                    double extensionReceiverLoad = partitionLoadCache[extensionPartition];
                    
                    ////System.out.println("Receiver delta of affine partition " + extensionPartition + " is " + extensionReceiverDelta);

                    if (extensionReceiverLoad + extensionReceiverDelta <= Controller.MAX_LOAD_PER_PART
                            || extensionReceiverDelta <= 0){
                        move.toPartition = extensionPartition;
                        move.sndDelta = m_graph.getSenderDelta(move.movingVertices, fromPartition, extensionPartition);
                        move.rcvDelta = extensionReceiverDelta;
                        move.rcvLoad = extensionReceiverLoad;
                        return nextHotTuplePos;
                    }
                }
                
                if (leastLoadedPartition != move.toPartition && leastLoadedPartition != fromPartition){

                    double leastLoadedReceiverDelta = m_graph.getReceiverDelta(move.movingVertices, leastLoadedPartition);
                    double leastLoadedReceiverLoad = partitionLoadCache[leastLoadedPartition];
                    
                    ////System.out.println("Receiver delta of least loaded partition " + leastLoadedPartition + " is " + leastLoadedReceiverDelta + " and load is " + leastLoadedReceiverLoad);

                    if (leastLoadedReceiverLoad < move.rcvDelta &&
                            (leastLoadedReceiverLoad + leastLoadedReceiverDelta < Controller.MAX_LOAD_PER_PART
                                    || leastLoadedReceiverDelta <= 0)){
                        move.toPartition = leastLoadedPartition;
                        move.sndDelta = m_graph.getSenderDelta(move.movingVertices, fromPartition, leastLoadedPartition);
                        move.rcvDelta = leastLoadedReceiverDelta;
                        move.rcvLoad = leastLoadedReceiverLoad;
                        return nextHotTuplePos;
                    }
                }
                return nextHotTuplePos;
            }
                
            ////System.out.println("Could not expand");
            move.wasExtended = false;
            return nextHotTuplePos;

        } // END if(!movedVertices.isEmpty())

        return nextHotTuplePos;

    }

    private int getMostAffinePartition (int vertex){    

        Int2DoubleMap adjacency = AffinityGraph.m_edges.get(vertex);

        if(adjacency == null){
            return -1;
        }

        double currAffinity = -1;
        int currPart = -1;

        for(Int2DoubleMap.Entry edge : adjacency.int2DoubleEntrySet()){

            int adjacentVertex = edge.getIntKey();
            double affinity = edge.getDoubleValue();

            if (affinity > currAffinity){
                currPart = AffinityGraph.m_vertexPartition.get(adjacentVertex);
                currAffinity = affinity;
            }
        }

        return currPart;
    }
    
    /*
     * finds the vertex with the highest affinity
     */
    protected int getMostAffineExtension(IntSet vertices, int senderPartition){

        double maxAffinity = -1;
        int res = 0;

        for(int vertex : vertices){

            // DEBUG
//            if(count_iter == 202){
//                ////System.out.println("Looking at the adjacency list of vertex " + AffinityGraph.m_vertexName.get(vertex));
//            }

            Int2DoubleMap adjacency = AffinityGraph.m_edges.get(vertex);
            if(adjacency != null){

                for(Int2DoubleMap.Entry edge : adjacency.int2DoubleEntrySet()){

                    int adjacentVertex = edge.getIntKey();
                    double affinity = edge.getDoubleValue();

                    // DEBUG
//                        ////System.out.println("Looking at adjacent vertex " + AffinityGraph.m_vertexName.get(adjacentVertex)
//                            + " with affinity " + affinity);

                    if (affinity > maxAffinity
                            && AffinityGraph.m_vertexPartition.get(adjacentVertex) == senderPartition
                            && !vertices.contains(adjacentVertex)) {

                        //DEBUG
//                        if(count_iter == 202){
//                            ////System.out.println("Picked adjacent vertex " + AffinityGraph.m_vertexName.get(adjacentVertex)
//                            + " with affinity " + affinity);
//                        }

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
                        vertexSingleton.remove(adjacentVertex);

                        if (affinity > maxAffinity
                                && !vertices.contains(adjacentVertex)
                                && newVertexPartition != senderPartition
                                && m_graph.getSenderDelta(vertexSingleton, newVertexPartition, false) <= 0) {
                            maxAffinity = affinity;
                            res = adjacentVertex;

//                            if(count_iter == 202){
//                                ////System.out.println("Picked adjacent vertex " + AffinityGraph.m_vertexName.get(adjacentVertex)
//                                + " with affinity " + affinity);
//                            }
                        }
                    }
                }
            }            
        }
        
        ////System.out.println("Max affinity: " + maxAffinity);

        return res;
    }

    private void writeMove(BufferedWriter writer, Move move){
        try {
            String vertexName = m_graph.getTupleName(move.startingHotTuple);
            int original_partition = m_graph.getOriginalPartition(move.startingHotTuple);

            writer.write(vertexName + ";" + original_partition + "," + move.toPartition);
            writer.newLine();

            for(int vertex : move.movingVertices){
                if (vertex != move.startingHotTuple) {
                    vertexName = m_graph.getTupleName(vertex);
                    original_partition = m_graph.getOriginalPartition(vertex);

                    writer.write(vertexName + ";" + original_partition + "," + move.toPartition);
                    writer.newLine();
                }
            }
            writer.newLine();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private boolean revertMoves(IntList activePartitions){

/*
        int topk = Controller.TOPK;
        for (int partition : activePartitions) {
            topk = Math.min(m_graph.numVertices(partition), topk);
        }

        System.out.println("Getting hot vertices");

        IntList hotVerticesList = m_graph.getHottestVertices(activePartitions, topk);

        System.out.println("Hot vertices " + m_graph.verticesToString(hotVerticesList));
*/
        Path movesFile = FileSystems.getDefault().getPath(".", "moves.log");
        BufferedReader reader;

        try {
            reader = Files.newBufferedReader(movesFile, Charset.forName("US-ASCII"));

            String line = "";
            line = reader.readLine();
            
            while (line != null) {
                System.out.println("Reading line " + line);

                String[] lineSplit = line.split(";");
                String oldHotTupleName = lineSplit[0];

                boolean stillHot = false;
/*
                for (int hotTuple : hotVerticesList) {
                    String hotTupleName = m_graph.getTupleName(hotTuple);
                    if (hotTupleName.equals(oldHotTupleName)) {
                        stillHot = true;
                        break;
                    }
                }

                System.out.println("Still hot? " + stillHot);
*/
                if (!stillHot) {
                    // move hot tuple in the graph (if present) back to the fromPartition
                    String[] partitions = lineSplit[1].split(",");
                    int fromPartition = Integer.parseInt(partitions[0]);
                    int toPartition = Integer.parseInt(partitions[1]);

                    m_graph.moveVertex(oldHotTupleName, toPartition, fromPartition);

                    line = reader.readLine();

                    System.out.println("Reading also line " + line);

                    // move all other tuples in the clump back to the fromPartition
                    while (line != null && line.length() > 0) {
                        lineSplit = line.split(";");
                        String tupleName = lineSplit[0];
                        partitions = lineSplit[1].split(",");
                        fromPartition = Integer.parseInt(partitions[0]);
                        toPartition = Integer.parseInt(partitions[1]);

                        m_graph.moveVertex(tupleName, toPartition, fromPartition);

                        line = reader.readLine();
                        System.out.println("Reading also line " + line);
                    }
                } else {
                    // skip - read through the current move until the end
                    while (line != null && line.length() > 0) {
                        line = reader.readLine();
                        System.out.println("Skipping also line " + line);
                    }
                }

                line = reader.readLine();
            }

        } catch (IOException e) {
            LOG.error("Controller failed while reading moves file " + movesFile.toString());
            System.out.println("Controller failed while reading moves file " + movesFile.toString());
            return false;
        }

        return true;
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
