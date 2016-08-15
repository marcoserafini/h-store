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
import org.voltdb.CatalogContext;

public class SimplePartitioner extends PartitionerAffinity {

    private static final Logger LOG = Logger.getLogger(SimplePartitioner.class);

    private class SimpleAffinityGraph extends AffinityGraph {

        public SimpleAffinityGraph (boolean tupleGranularity, CatalogContext catalogContext, File planFile,
                                    Path[] logFiles, Path[] intervalFiles, int noPartitions) throws Exception {
            super(tupleGranularity, catalogContext, planFile, logFiles, intervalFiles, noPartitions);
        }

        @Override
        public double getLoadPerPartition(int partition) {

            if (!AffinityGraph.isActive(partition)){
                return 0;
            }

            IntSet vertices = AffinityGraph.m_partitionVertices.get(partition);
            double load = 0;
            for(int vertex : vertices){
                // local accesses
                load += AffinityGraph.m_vertices.get(vertex);
                // remote accesses
                int fromPartitionSite = PlanHandler.getSitePartition(partition);
                Int2DoubleMap adjacencyList = AffinityGraph.m_edges.get(vertex);
                if(adjacencyList != null){
                    for(Int2DoubleMap.Entry edge : adjacencyList.int2DoubleEntrySet()){
                        int toPartition = edge.getIntKey();
                        int toPartitionSite = PlanHandler.getSitePartition(toPartition);
                        if(toPartitionSite != fromPartitionSite){
                            load += edge.getDoubleValue() * Controller.DTXN_COST;
                        }
                        else if(toPartition != partition){
                            load += edge.getDoubleValue() * Controller.LMPT_COST;
                        }
                    }
                }
            }
            return load;
        }


        @Override
        public double getGlobalDelta(IntSet movingVertices, int toPartition) {

            if (movingVertices == null || movingVertices.isEmpty()){
                LOG.debug("Trying to move an empty set of vertices");
                return 0;
            }

            assert(movingVertices.size() == 1);

            double delta = 0;

            int toSite = (toPartition == -1) ? -1 : PlanHandler.getSitePartition(toPartition);

            for(int movingVertex : movingVertices){
                int fromPartition = AffinityGraph.m_vertexPartition.get(movingVertex);
                int fromSite = PlanHandler.getSitePartition(fromPartition);

                assert(fromPartition != toPartition);

                Int2DoubleOpenHashMap adjacency = AffinityGraph.m_edges.get(movingVertex);
                if(adjacency != null){

                    for (Int2DoubleMap.Entry edge : adjacency.int2DoubleEntrySet()){
                        int adjacentPartition = edge.getIntKey();
                        double edgeWeight = edge.getDoubleValue();

                        if (adjacentPartition == fromPartition){
                            double k = (fromSite == toSite) ? Controller.LMPT_COST : Controller.DTXN_COST;
                            delta += edgeWeight * k;
                        }
                        else if (adjacentPartition == toPartition){
                            double k = (fromSite == toSite) ? Controller.LMPT_COST : Controller.DTXN_COST;
                            delta -= edgeWeight * k;
                        }
                        else{
                            int adjacentVertexSite = PlanHandler.getSitePartition(adjacentPartition);
                            double h = 0;
                            if (adjacentVertexSite == fromSite && adjacentVertexSite != toSite){
                                h = Controller.DTXN_COST - Controller.LMPT_COST;
                            }
                            else if (adjacentVertexSite != fromSite && adjacentVertexSite == toSite){
                                h = Controller.LMPT_COST - Controller.DTXN_COST;
                            }
                            delta += edgeWeight * h;
                        }
                    }
                }
            }

            return delta;
        }

        @Override
        public double getReceiverDelta(IntSet movingVertices, int toPartition) {

            if (movingVertices == null || movingVertices.isEmpty()){
                LOG.debug("Trying to move an empty set of vertices");
                return 0;
            }

            assert(movingVertices.size() == 1);

            double delta = 0;
            int toSite = (toPartition == -1) ? -1 : PlanHandler.getSitePartition(toPartition);

            for(int movingVertex : movingVertices){

                double vertexWeight = AffinityGraph.m_vertices.get(movingVertex);
                if (vertexWeight == AffinityGraph.m_vertices.defaultReturnValue()){
                    LOG.debug("Cannot include external node for delta computation");
                    throw new IllegalStateException("Cannot include external node for delta computation");
                }

                int fromPartition = AffinityGraph.m_vertexPartition.get(movingVertex);
                int fromSite = PlanHandler.getSitePartition(fromPartition);

                assert(fromPartition != toPartition);

                delta += vertexWeight;

                Int2DoubleOpenHashMap adjacency = AffinityGraph.m_edges.get(movingVertex);
                if(adjacency != null){

                    for (Int2DoubleMap.Entry edge : adjacency.int2DoubleEntrySet()){

                        int adjacentPartition = edge.getIntKey();
                        int adjacentVertexSite = PlanHandler.getSitePartition(adjacentPartition);

                        double edgeWeight = edge.getDoubleValue();

                        if (adjacentPartition == toPartition){
                            double k = (fromSite == toSite) ? Controller.LMPT_COST : Controller.DTXN_COST;
                            delta -= edgeWeight * k;
                        }
                        else if (adjacentVertexSite == toSite) {
                            delta += edgeWeight * Controller.LMPT_COST;
                        }
                        else{
                            delta += edgeWeight * Controller.DTXN_COST;
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

            assert(movingVertices.size() == 1);

            for(int movingVertex : movingVertices){

                double vertexWeight = AffinityGraph.m_vertices.get(movingVertex);
                if (vertexWeight == AffinityGraph.m_vertices.defaultReturnValue()){
                    LOG.debug("Cannot include external node for delta computation");
                    throw new IllegalStateException("Cannot include external node for delta computation");
                }

                int fromPartition = AffinityGraph.m_vertexPartition.get(movingVertex);
                assert (fromPartition == senderPartition);

                delta -= vertexWeight;

                Int2DoubleOpenHashMap adjacency = AffinityGraph.m_edges.get(movingVertex);
                if(adjacency != null){

                    for (Int2DoubleMap.Entry edge : adjacency.int2DoubleEntrySet()){

                        int adjacentPartition = edge.getIntKey();
                        int adjacentSite = PlanHandler.getSitePartition(adjacentPartition);
                        double edgeWeight = edge.getDoubleValue();

                        if (adjacentPartition == senderPartition){
                            double k = (toPartitionLocal) ? Controller.LMPT_COST : Controller.DTXN_COST;
                            delta += edgeWeight * k;
                        }
                        else if (adjacentSite == senderSite) {
                            delta -= edgeWeight * Controller.LMPT_COST;
                        }
                        else{
                            delta -= edgeWeight * Controller.DTXN_COST;
                        }
                    }
                }
            }

            return delta;
        }

//    protected double getDeltaVertices(IntSet movingVertices, int toPartition, boolean forSender) {
//        assert(movingVertices.size() == 1);
//        double delta = 0;
//        for(int vertex : movingVertices){
//
//            double vertexWeight = AffinityGraph.m_vertices.get(vertex);
//
//            int fromPartition = m_graph.getPartition(vertex);
//
//            double outPull= AffinityGraph.m_edges.get(vertex).get(Integer.toString(toPartition));
//            double inPull= AffinityGraph.m_edges.get(vertex).get(Integer.toString(fromPartition));
//
//            double outMultiplier;
//            if (toPartition == -1 || PlanHandler.getSitePartition(toPartition) != PlanHandler.getSitePartition(fromPartition)){
//                outMultiplier = DTXN_COST;
//            }
//            else{
//                outMultiplier = LMPT_COST;
//            }
//
//            if(forSender){
//                delta += vertexWeight;
//                delta -= outPull * outMultiplier;
//                delta += inPull * outMultiplier;
//            }
//            else{
//                delta -= vertexWeight;
//                delta -= outPull * outMultiplier;
//                delta += inPull * outMultiplier;
//            }
//        }
//        return delta;
//    }

        @Override
        public double getLoadVertices(IntSet vertices) {

            double load = 0;

            for(int vertex : vertices){

                // local accesses
                double vertexWeight = AffinityGraph.m_vertices.get(vertex);

                if (vertexWeight == AffinityGraph.m_vertices.defaultReturnValue()){
                    LOG.debug("Cannot include external node for delta computation");
                    throw new IllegalStateException("Cannot include external node for delta computation");
                }

                load += vertexWeight;

                // remote accesses
                int fromPartition = AffinityGraph.m_vertexPartition.get(vertex);
                int fromSite = PlanHandler.getSitePartition(fromPartition);

                Int2DoubleMap adjacencyList = AffinityGraph.m_edges.get(vertex);
                if(adjacencyList != null){

                    for(Int2DoubleMap.Entry edge : adjacencyList.int2DoubleEntrySet()){

                        int toPartition = edge.getIntKey();
                        double edgeWeight = edge.getDoubleValue();

                        if(toPartition != fromPartition){

                            int toSite = PlanHandler.getSitePartition(toPartition);
                            double h = (fromSite == toSite) ? Controller.LMPT_COST : Controller.DTXN_COST;
                            load += edgeWeight * h;
                        }

                    }
                }
            }
            return load;
        }
    }

    public SimplePartitioner (CatalogContext catalogContext, File planFile, Path[] logFiles, Path[] intervalFiles){
        
        try{
            m_graph = new SimpleAffinityGraph(false, catalogContext, planFile, logFiles, intervalFiles, Controller.MAX_PARTITIONS);
        }
        catch (Exception e){
            Controller.record("Problem while loading graph. Exiting");
            return;
        }
    }
    
    @Override
    public boolean repartition(boolean firstRepartition) {

        int addedPartitions = 0;

        IntList activePartitions = new IntArrayList(Controller.MAX_PARTITIONS);

        for(int i = 0; i < Controller.MAX_PARTITIONS; i++){
            if(AffinityGraph.isActive(i)){
                activePartitions.add(i);
            }
        }
        
        IntSet singleton = new IntOpenHashSet(1);

        // move border vertices        
//        for (int fromPart = 0; fromPart < Controller.MAX_PARTITIONS; fromPart ++){
//            
//            List<IntList> borderVertices = getBorderVertices(fromPart, Controller.MAX_MOVED_TUPLES_PER_PART);
//
//            for(int toPart = 0; toPart < Controller.MAX_PARTITIONS; toPart ++){
//                
//                for (int vertex : borderVertices.get(toPart)){
//                
//                    if (fromPart != toPart){
//                        singleton.clear();
//                        singleton.add(vertex);
//                        tryMoveVertices(singleton, fromPart, toPart);
//                    }
//                }
//            }
//        }
        
        // offload overloaded partitions
        
        IntList overloadedPartitions = new IntArrayList(Controller.MAX_PARTITIONS);
        
        for(int i = 0; i < Controller.MAX_PARTITIONS; i++){
            if(activePartitions.contains(i)){
                System.out.println(m_graph.getLoadPerPartition(i));
                if (m_graph.getLoadPerPartition(i) > Controller.MAX_LOAD_PER_PART){
                    overloadedPartitions.add(i);
                }
            }
        }
        
        if (! overloadedPartitions.isEmpty()){
            
            // move hot vertices
            for(int fromPart : overloadedPartitions){
                
                int numMovedVertices = 0;
                IntSet warmMovedVertices = new IntOpenHashSet();
                
                // loop over multiple added partitions
                while(m_graph.getLoadPerPartition(fromPart) > Controller.MAX_LOAD_PER_PART){

                    int topk = Math.min(m_graph.numVertices(fromPart), Controller.TOPK);
                    IntList hotVertices = m_graph.getHottestVertices(fromPart, topk);
    
                    for (int vertex : hotVertices){
                        
                        System.out.println("Considering vertex " + AffinityGraph.m_vertexName.get(vertex));
    
                        for (int toPart = 0; toPart < Controller.MAX_PARTITIONS; toPart ++){
    
                            if (fromPart != toPart){
                                singleton.clear();
                                singleton.add(vertex);
                                int newMovedVertices = tryMoveVertices(singleton, fromPart, toPart);
                                if (newMovedVertices > 0){ 
                                    numMovedVertices += newMovedVertices;
                                    warmMovedVertices.add(vertex);
                                    break;
                                }
                            }                    
                        }
                        if(m_graph.getLoadPerPartition(fromPart) <= Controller.MAX_LOAD_PER_PART){
                            break;
                        }
    
                    }
                    
                    if (m_graph.getLoadPerPartition(fromPart) > Controller.MAX_LOAD_PER_PART){
                        numMovedVertices += moveColdChunks(fromPart, hotVertices, warmMovedVertices, activePartitions, numMovedVertices);
                    }
                    
                    if (m_graph.getLoadPerPartition(fromPart) > Controller.MAX_LOAD_PER_PART){
                        
                        if(activePartitions.size() < Controller.MAX_PARTITIONS 
                                && addedPartitions < Controller.MAX_PARTITIONS_ADDED){
        
                            // We fill up low-order partitions first to minimize the number of servers
                            addedPartitions++;
                            for(int i = 0; i < Controller.MAX_PARTITIONS; i++){
                                if(!activePartitions.contains(i)){
                                    activePartitions.add(i);
                                    break;
                                }
                            }
                        }
                        else{
                            System.out.println("Cannot add new partition to offload " + overloadedPartitions);
                            return false;
                        }
        
                    }
                }
            }
        }
        else{
            // try to scale in
            scaleIn(activePartitions);
        }
        return true;
    }
    
    @Override
    protected void updateAttractions (Int2DoubleMap adjacency, double[] attractions){
        for (int toVertex : adjacency.keySet()){
            
            double edge_weight = adjacency.get(toVertex);
            attractions[toVertex] += edge_weight;
        } // END for (String toVertex : adjacency.keySet())
    }

}
