/**
 * @author Marco
 */

package org.qcri.affinityplanner;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import it.unimi.dsi.fastutil.ints.*;

import org.apache.log4j.Logger;
import org.hsqldb.Error;
import org.voltdb.CatalogContext;

public class AffinityGraph {
  
    private static final Logger LOG = Logger.getLogger(AffinityGraph.class);

    // determines granularity of edges, either vertex - vertex of vertex - partition 
    private static boolean m_tupleGranularity = false;
    
    // fromVertex -> adjacency list, where adjacency list is a toVertex -> edgeWeight map or toPartition -> edgeWeight map, depending on granularity 
    // (weight of edge is # of txns touching both partitions per second)
    protected static Int2ObjectOpenHashMap<Int2DoubleOpenHashMap> m_edges = new Int2ObjectOpenHashMap <> ();
    
    // vertex -> full name
    protected static ConcurrentHashMap<Integer,String> m_vertexName = new ConcurrentHashMap <> ();
    protected static ConcurrentHashMap<String,Integer> m_nameVertex = new ConcurrentHashMap <> ();

    // vertex -> weight map (number of txns accesses the vertext per second)
    protected static final Int2DoubleOpenHashMap m_vertices = new Int2DoubleOpenHashMap (1000);
    
    // partition -> vertex and vertex -> partition mappings
    protected static List<IntOpenHashSet> m_partitionVertices = new ArrayList<IntOpenHashSet> ();
    protected static Int2IntOpenHashMap m_vertexPartition = new Int2IntOpenHashMap ();
    protected static Int2IntOpenHashMap m_vertexPartition_original = new Int2IntOpenHashMap ();

    private static PlanHandler m_plan_handler = null;
    
    private static long[] m_intervalsInSecs;
    
    private static class LoaderThread implements Runnable {
        private Path[] logFiles;
        private AtomicInteger nextLogFileCounter;
        
        public LoaderThread(Path[] logFiles, AtomicInteger nextLogFileCounter){
            this.logFiles = logFiles;
            this.nextLogFileCounter = nextLogFileCounter;
        }

        @Override
        public void run() {
            try {
                int nextLogFile = nextLogFileCounter.getAndIncrement();
                
                while (nextLogFile < logFiles.length){
                    
                    double increment = 1.0/m_intervalsInSecs[nextLogFile];
                    
                    loadLogFile(logFiles[nextLogFile], increment);
                    
                    nextLogFile = nextLogFileCounter.getAndIncrement();                    
                }
            } catch (Exception e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
        
        private void loadLogFile(Path logFile, double normalizedIncrement) throws Exception{
            
            // init local data structures to be merged later
            Int2IntOpenHashMap vertexPartition = new Int2IntOpenHashMap();
            Int2DoubleOpenHashMap vertices = new Int2DoubleOpenHashMap (1000);
            List<IntOpenHashSet> partitionVertices = new ArrayList<IntOpenHashSet> ();
            for (int i = 0; i < m_partitionVertices.size(); i++){
                partitionVertices.add(new IntOpenHashSet());
            }

            
            System.out.println("Loading from file " + logFile);

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
//                throw new Exception();
            }

//            System.out.println("Tran ID = " + currTransactionId);
            
            HashMap<String, IntOpenHashSet> transactions = new HashMap<String,IntOpenHashSet>();

            // PROCESS LINE
            while(line != null){
                
                String[] fields = line.split(";");

                // if finished with one transaction, update graph and clear before moving on

                if (fields[0].equals("END")){
                    
                    String transaction_id = fields[1]; 
                    IntOpenHashSet curr_transaction = transactions.get(transaction_id);
                    
                    if(curr_transaction != null){

                        for(int from : curr_transaction){
                            // store partition mappings for FROM vertex
                            String fromName = m_vertexName.get(from);
                            int partition = m_plan_handler.getPartition(fromName);
                            try {
                                partitionVertices.get(partition).add(from);
                            } catch (ArrayIndexOutOfBoundsException e){
                                System.out.println("Problem with plan: Partition for tuple " + fromName
                                        + " is reported to be " + partition + " but it is out of bound");
                                throw e;
                            }
                            vertexPartition.put(from, partition);
                            
                            // update FROM vertex in graph
                            double currentVertexWeight = vertices.get(from);
                            if (currentVertexWeight == vertices.defaultReturnValue()){
                                vertices.put(from, normalizedIncrement);
                            }
                            else{
                                vertices.put(from, currentVertexWeight + normalizedIncrement);                         
                            }
                            
                            // update FROM -> TO edges
                            Int2DoubleOpenHashMap adjacency = null;
    
                            synchronized(m_edges){
                                adjacency = m_edges.get(from);
                                if(adjacency == null){
                                    adjacency = new Int2DoubleOpenHashMap ();
                                    m_edges.put(from, adjacency);
                                }
                            }
    
                            IntOpenHashSet visitedVertices = new IntOpenHashSet();
                            for(int to : curr_transaction){
                                
                                if (from != to && !visitedVertices.contains(to)){
                                    visitedVertices.add(to);
                                    
                                    // if lower granularity, edges link vertices to partitions, not other vertices
                                    if(!m_tupleGranularity){
                                        String toName = m_vertexName.get(to);
                                        to = m_plan_handler.getPartition(toName);
                                    }
                                    
                                    synchronized(adjacency){
                                        double currentEdgeWeight = adjacency.get(to);
                                        if (currentEdgeWeight == 0){ // TODO use adjacency.defaultReturnValue() instead of 0
                                            adjacency.put(to, normalizedIncrement);
                                        }
                                        else{
                                            adjacency.put(to, currentEdgeWeight + normalizedIncrement);
                                        }
                                    }
                                }
                            } // END for(String to : curr_transaction)
    
                        } // END for(String from : curr_transaction)
    
                        transactions.remove(transaction_id);
                    } // END if (curr_transaction != null)
                    
                    //                    System.out.println("Tran ID = " + currTransactionId);
                } // END if (line.equals("END"))

                else{
                    // add the vertex to the transaction
                    String transaction_id = fields[0];
                    String tuple_name = fields[1];
                    int hash = fields[1].hashCode();
                    
                    if (!m_vertexName.containsKey(hash) || !m_vertexName.get(hash).equals(tuple_name)){
                        // deal with hash collisions. access the global map to guarantee uniqueness. skip in normal case 
                        String prev = m_vertexName.putIfAbsent(hash, tuple_name);
                        while (prev != null && !prev.equals(tuple_name)){
                            // deterministic re-hashing
                            hash ^= (hash >>> 20) ^ (hash >>> 12);
                            hash = hash ^ (hash >>> 7) ^ (hash >>> 4);
                            prev = m_vertexName.putIfAbsent(hash, tuple_name);
                        }
                        m_nameVertex.put(tuple_name, hash);
                    }

                    IntOpenHashSet curr_transaction = transactions.get(transaction_id);
                    if (curr_transaction == null){
                        curr_transaction = new IntOpenHashSet ();
                        transactions.put(transaction_id, curr_transaction);
                    }
                    curr_transaction.add(hash);

                } // END if (!line.equals("END"))

                /* this is what one would do to count different accesses within the same transaction. 
                 * For the moment I count only the number of transactions accessing a tuple
                Double weight = transaction.get(vertex[1]);
                if (weight == null){
                    transaction.put(vertex[1], 1.0/intervalsInSecs[currLogFile]);
                }
                else{
                    transaction.put(vertex[1], (weight+1.0)/intervalsInSecs[currLogFile]);
                } 
                */
                
                // READ NEXT LINE
                try {
                    line = reader.readLine();
                } catch (IOException e) {
                    Controller.record("Error while reading file " + logFile.toString() + "\n Stack trace:\n" + Controller.stackTraceToString(e));
                    System.out.println("Error while reading file " + logFile.toString() + "\n Stack trace:\n" + Controller.stackTraceToString(e));
                    throw e;
                }
            }// END  while(line != null)
            
            // merge local data structures
            synchronized(m_vertexPartition){
                m_vertexPartition.putAll(vertexPartition);
            }
            for (int i = 0; i < partitionVertices.size(); i++){
                IntSet localVertices = partitionVertices.get(i);
                synchronized(m_partitionVertices){
                    m_partitionVertices.get(i).addAll(localVertices);
                }
            }
            for (Int2DoubleMap.Entry vertex : vertices.int2DoubleEntrySet()){
                double curr_weight = 0;
                synchronized(m_vertices){
                    curr_weight = m_vertices.get(vertex.getIntKey());
                    if(curr_weight != vertices.defaultReturnValue()){
                        curr_weight += vertex.getDoubleValue();
                    }
                    else{
                        curr_weight = vertex.getDoubleValue();
                    }
                    m_vertices.put(vertex.getIntKey(), curr_weight);
                }
            }
        }
    }
    
    public AffinityGraph(boolean tupleGranularity, CatalogContext catalogContext, File planFile, 
            Path[] logFiles, Path[] intervalFiles, int noPartitions) throws Exception {
        m_tupleGranularity = tupleGranularity;

        BufferedReader reader;

        for (int i = 0; i < noPartitions; i++){
            m_partitionVertices.add(new IntOpenHashSet());
        }

        try {
            m_plan_handler = new PlanHandler(planFile, catalogContext);
        } catch (Exception e) {
            Controller.record("Could not create plan handler " + Controller.stackTraceToString(e));
            throw e;
        }

        // read monitoring intervals for all sites - in seconds
        m_intervalsInSecs = new long[intervalFiles.length];
        int currInterval = 0;
        for (Path intervalFile : intervalFiles){
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
        
        // scan files for all partitions
        int threads = Math.min(Controller.LOAD_THREADS, logFiles.length);
        System.out.println("Using " + threads + " threads");
        Thread[] loadingThreads = new Thread[threads];
        AtomicInteger nextLogFileCounter = new AtomicInteger(0);
        
        for (int currLogFile = 0; currLogFile < threads; currLogFile++){
            
            Thread loader = new Thread (new LoaderThread (logFiles, nextLogFileCounter));
            loadingThreads[currLogFile] = loader;
            loader.start();
        }
        
        // wait for all loader threads to finish
        for (Thread loader : loadingThreads){
            loader.join();
        }
    }

    public void moveHotVertices(IntSet movedVertices, int toPartition) {
        for (int movedVertex : movedVertices){
            int fromPartition = m_vertexPartition.get(movedVertex);
            moveVertexInGraph(movedVertex, fromPartition, toPartition);

            if( ! m_vertexPartition_original.containsKey(movedVertex)){
               m_vertexPartition_original.put(movedVertex, fromPartition);
            }

            // update plan too
            // format of vertices is <TABLE>,<VALUE>
            String movedVertexName = m_vertexName.get(movedVertex);

            moveVertexInPlan(movedVertexName, fromPartition, toPartition);
        }
        
//        System.out.println(m_plan_handler.toString() + "\n");
    }

    public void moveVertex (String movedVertexName, int fromPartition, int toPartition){
        if (m_nameVertex != null && m_nameVertex.contains(movedVertexName)) {
            int movedVertex = m_nameVertex.get(movedVertexName);
            moveVertexInGraph(movedVertex, fromPartition, toPartition);
        }
        moveVertexInPlan(movedVertexName, fromPartition, toPartition);
    }

    private void moveVertexInGraph(int movedVertex, int fromPartition, int toPartition){
        m_partitionVertices.get(fromPartition).remove(movedVertex);
        m_partitionVertices.get(toPartition).add(movedVertex);
        m_vertexPartition.put(movedVertex, toPartition);
    }

    private void moveVertexInPlan (String movedVertexName, int fromPartition, int toPartition){
        String [] fields = movedVertexName.split(",");

        if(Controller.ROOT_TABLE == null){
//                System.out.println("table: " + fields[0] + " from partition: " + fromPartition + " to partition " + toPartition);
//                System.out.println("remove ID: " + fields[1]);
            boolean res = m_plan_handler.removeTupleId(fields[0], fromPartition, Long.parseLong(fields[1]));
            if (!res){
                System.out.println("Problem removing " + movedVertexName + " from partition " + fromPartition);
                System.exit(0);
            }
//                System.out.println("After removal");
//                System.out.println(m_plan_handler.toString() + "\n");
            m_plan_handler.addPartition(fields[0], toPartition);
//                System.out.println("After adding partition");
//                System.out.println(m_plan_handler.toString() + "\n");
            res = m_plan_handler.addRange(fields[0], toPartition, Long.parseLong(fields[1]), Long.parseLong(fields[1]));
            if (!res){
                System.out.println("Problem adding " + movedVertexName + " to partition " + toPartition);
                System.exit(0);
            }
//                System.out.println("After adding range");
//                System.out.println(m_plan_handler.toString() + "\n");
//                System.exit(0);
        }
        else{
            m_plan_handler.removeTupleIdAllTables(fromPartition, Long.parseLong(fields[1]));
            m_plan_handler.addRangeAllTables(toPartition, Long.parseLong(fields[1]), Long.parseLong(fields[1]));
        }
    }
    
    public String getTupleName(int hash){
        return m_vertexName.get(hash);
    }

    public void mergePartitions(int sourcePartition, int destinationPartition){
        m_plan_handler.mergePartitions(sourcePartition, destinationPartition);
    }
    
    public void moveColdRange(String table, Plan.Range movedRange, int fromPart, int toPart){     
        m_plan_handler.moveColdRange(table, movedRange, fromPart, toPart);
    }
    
    public void moveColdRangeAllTables(Plan.Range movedRange, int fromPart, int toPart){
        m_plan_handler.moveColdRangeAllTables(movedRange, fromPart, toPart);
    }
    
    public int getPartition(int vertex){
        String vertexName = m_vertexName.get(vertex);
        return m_plan_handler.getPartition(vertexName);
    }

    public int getOriginalPartition(int vertex){
        return m_vertexPartition_original.get(vertex);
    }
    
    public static boolean isActive(int partition){
        return m_plan_handler.isNotEmpty(partition);
    }
    
    public void planToJSON(String newPlanFile){
        m_plan_handler.toJSON(newPlanFile);
    }
    
    
    
    public void toMetisFile(Path file, Path mapOut){
        LOG.info("Writing graph for " + file.toString() +" . Number of vertices: " + m_vertices.size());
        BufferedWriter writer, mapWriter = null;
        try {
            writer = Files.newBufferedWriter(file, Charset.forName("US-ASCII"), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            String graphInfo = "011"; //ABC A:VertexSize B:VertexWeight C:EdgeWeight
            int edgeCount=0;
            for(int v_id : m_vertices.keySet()){                
                if (m_edges.get(v_id)!=null)
                    edgeCount+=m_edges.get(v_id).size();
            }
            String header = String.format("%s %s %s",m_vertices.size(), (int)(edgeCount/2), graphInfo);
            writer.write(header);
            writer.newLine();
            
            //Need an ordered list of vertexes 1 .. N
            Int2IntOpenHashMap vert_to_increment = new Int2IntOpenHashMap(m_vertices.size());
            int[] vert_ids = new int[m_vertices.size()+1];
            int count = 1;
            for (int vert_hash: m_vertices.keySet()){
                vert_ids[count] = vert_hash;
                vert_to_increment.put(vert_hash, count);
                count++;
            }
            if (mapOut != null){
                mapWriter = Files.newBufferedWriter(mapOut, Charset.forName("US-ASCII"), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                for (int i = 1; i < vert_ids.length; i++ ){
                    mapWriter.write(String.format("%s", vert_ids[i]));
                    mapWriter.newLine();
                }
            }

            //Each line is [vert size] [edge to vertex id 1] [edge 1 weight] [edge to vertex id 2] [edge 2 weight] ...
            //vertex ID is implicitly line number (starting at 1)
            for(int incrID = 1; incrID < vert_ids.length; incrID++){
                //TODO check on format of weights
                int vert_hash = vert_ids[incrID];
                double vert_weight = m_vertices.get(vert_hash);
                //StringBuilder sb = new StringBuilder(String.format("%s %d", m_vertex_to_name.get(vert_hash), (int)(vert_weight*100)));//TODO casting to int
                StringBuilder sb = new StringBuilder(String.format("%d", (int)(vert_weight*100)));//TODO casting to int
                
                for (Entry<Integer, Double> edge: m_edges.get(vert_hash).entrySet()){
                    int weight = (int) (edge.getValue() * 100);
                    sb.append(String.format(" %d %d", vert_to_increment.get(edge.getKey()),weight ));//TODO casting to int
                }
                writer.write(sb.toString());
                writer.newLine();
            }
            writer.close();
            if (mapWriter!=null) mapWriter.close();
        } catch (IOException e) {
            e.printStackTrace();
            Controller.record("Error while opening file " + file.toString());
            System.out.println("Error while opening file " + file.toString());
            return;
       }        
        
    }

    
    public String planToString(){
        return m_plan_handler.toString();
    }
    
    public String[] getTableNames(){
        return m_plan_handler.table_names;
    }
    
    public List<Plan.Range> getAllRanges (String table, int partition){
        return m_plan_handler.getAllRanges(table, partition);
    }
    
    public double getColdTupleWeight (int partition){
        return 1.0 / m_intervalsInSecs[partition] / Controller.COLD_TUPLE_FRACTION_ACCESSES;
    }
    
    public int getHotVertexCount(int partition){
        return m_partitionVertices.get(partition).size();
    }
    
    public PlanHandler clonePlan(){
        return m_plan_handler.clone();
    }
    
    public double getColdIncrement(int fromPartition){
        return 1.0 / m_intervalsInSecs[fromPartition] / Controller.COLD_TUPLE_FRACTION_ACCESSES;
    }
    
    public List<Plan.Range> getRangeValues(String table, int partition, long from, long to){
        return m_plan_handler.getRangeValues(table, partition, from, to);
    }
    
    public void toFile(Path file){
        
        System.out.println("Writing graph.\nNumber of vertices: " + m_edges.size());
        long edgesCount = 0;
        for (Int2DoubleOpenHashMap adjacency :m_edges.values()){
            edgesCount += adjacency.size();
        }
        edgesCount = edgesCount / 2;
        System.out.println("Number of edges: " + edgesCount);
        
        BufferedWriter writer;
        String s;
        double totalWeight = 0;
        try {
            writer = Files.newBufferedWriter(file, Charset.forName("US-ASCII"), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            for(int vertex : m_vertices.keySet()){
                s = "Vertex " + m_vertexName.get(vertex) + " - weight " + m_vertices.get(vertex);
                totalWeight += m_vertices.get(vertex);
                writer.write(s, 0, s.length());
                writer.newLine();
                Int2DoubleMap adjacency = m_edges.get(vertex);
                if(adjacency == null){
                    writer.newLine();
                    continue;
                }
                for (Int2DoubleMap.Entry edge : adjacency.int2DoubleEntrySet()){
                    s = m_vertexName.get(edge.getIntKey()) + " - weight " + edge.getDoubleValue();
                    writer.write(s, 0, s.length());
                    writer.newLine();
                }
                writer.newLine();
            }
            s = "Total weight: " + totalWeight;
            writer.write(s, 0, s.length());
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
            Controller.record("Error while opening file " + file.toString());
            System.out.println("Error while opening file " + file.toString());
            return;
       }
    }
    
    public void toFileMPT(Path file){
        BufferedWriter writer;
        try{
            writer = Files.newBufferedWriter(file, Charset.forName("US-ASCII"), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            
            for (int fromVertex: m_edges.keySet()){
                int sourcePart = m_vertexPartition.get(fromVertex);
                Int2DoubleOpenHashMap adjList = m_edges.get(fromVertex);
                for (int toVertex: adjList.keySet()){
                    int destPart = m_vertexPartition.get(toVertex);
                    if (sourcePart != destPart){
                        String s = "From: " + m_vertexName.get(fromVertex) + " weight  " + m_vertices.get(fromVertex) + 
                                " in partition " + sourcePart +
                                " == To: " + m_vertexName.get(toVertex) + " weight  " + m_vertices.get(toVertex) +
                                " in partition " + destPart +
                                " == Edge weight: " + adjList.get(toVertex);
                        writer.write(s, 0, s.length());
                        writer.newLine();
                    }
                }
            }
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
            Controller.record("Error while opening file " + file.toString());
            System.out.println("Error while opening file " + file.toString());
            return;
       }       
    }
    
    // get a set of moves in the form tupleId -> toPartition
    public void movesToMultipleReceivers (Int2IntOpenHashMap newVertexPartition){
        
        IntSet movedVertices = new IntOpenHashSet(1);
        
        for (Int2IntMap.Entry entry: newVertexPartition.int2IntEntrySet()){
            
            int vertex = entry.getIntKey();
            int toPartition = entry.getIntValue();
            
            movedVertices.add(vertex);
            
            moveHotVertices(movedVertices, toPartition);
            
            movedVertices.clear();
            
        }
                
    }
    
    public int numVertices(int partition){
        return m_partitionVertices.get(partition).size();
    }

    public int numVertices(){
        return m_vertexPartition.size();
    }

    public int numEdges(){
        int sum = 0;
        for (Int2DoubleOpenHashMap adjacencyList: m_edges.values()){
            sum += adjacencyList.size();
        }
        return sum / 2;
    }

    public String verticesToString(IntSet set){
        StringBuilder res = new StringBuilder();
        for (int val : set){
            res.append(m_vertexName.get(val) + " from partition " + m_vertexPartition.get(val) + "\n");
        }
        return res.toString();
    }

    public void toCPLEXFile(Path file) {

        // ACCORDION -> clay nomeclature: HOSTS -> partition, PARTITION -> hottest vertex

        IntArrayList hottestVertices = new IntArrayList();

        int k = 1000;

        for (int i = 0; i < Controller.MAX_PARTITIONS; i++){
            hottestVertices.addAll(getHottestVertices(i,k));
        }

        try{
            PrintWriter out = new PrintWriter(new FileWriter(file.toString()));
            out.println("param P := " + hottestVertices.size() + ";");
            out.println("param K := 1;");

            // assumes that all active partitions are consecutive
            int numPartitions = 0;
            for(int i = 0; i < Controller.MAX_PARTITIONS; i++){
                if(AffinityGraph.isActive(i)){
                    numPartitions++;
                }
            }
            out.println("param CURR_HOSTS := " + numPartitions + ";");

            out.println("param MAX_HOSTS := " + Controller.MAX_PARTITIONS + ";");
            out.println("param MAX_CAPACITY := " + Controller.MAX_LOAD_PER_PART + ";");
            out.println("param DTXN_COST := " + Controller.DTXN_COST + ";");

            out.print("param INITIAL:\t");
            for (int i = 0; i < Controller.MAX_PARTITIONS; i++) out.print((i+1) + "\t");
            out.println(":=");

            int currPos = 0;
            for (int hash : hottestVertices) {

                currPos++;
                out.print(currPos + "\t");

                int currPartition = m_vertexPartition.get(hash);

                for (int j = 0; j < Controller.MAX_PARTITIONS; j++) {
                    // what is i what is j?
                    if(currPartition == j){
                        out.print(1 + "\t");
                    }
                    else{
                        out.print(0 + "\t");
                    }
                }
                out.println();
            }
            out.println(";");

            out.println("param \tRATE :=");
            currPos = 0;
            for (int hash : hottestVertices) {
                currPos++;
                out.println(currPos + "\t" + m_vertices.get(hash));
            }
            out.println(";");

            out.print("param DTXN_PER_PART:\t");
            for (int i = 0; i < hottestVertices.size(); i++) out.print((i+1) + "\t");
            out.println(":=");

            currPos = 0;
            for (int hashFrom : hottestVertices) {

                currPos++;
                out.print((currPos) + "\t");

                Int2DoubleOpenHashMap adjacency = m_edges.get(hashFrom);

                for (int hashTo : hottestVertices) {
                    if (hashFrom != hashTo){
                        if(adjacency.containsKey(hashTo)) {
                            out.print((int) Math.ceil(adjacency.get(hashTo)) + "\t");
                        }
                        else{
                            out.print("0\t");
                        }
                    }
                    else out.print("0\t");
                }
                out.println();
            }
            out.println(";");

            out.close();
        } catch (IOException e) {
            e.printStackTrace();
            Controller.record("Error while opening file " + file.toString());
            System.out.println("Error while opening file " + file.toString());
            return;
        }
    }

    // #######################################################
    // ############ DEFAULT LOAD-RELATED FUNCTIONS ###########
    // #######################################################

    public double getSenderDelta(IntSet movingVertices, int senderPartition, int toPartition){
        boolean toPartitionLocal = (PlanHandler.getSitePartition(senderPartition) == PlanHandler.getSitePartition(toPartition));
        return getSenderDelta(movingVertices, senderPartition, toPartitionLocal);
    }

    public double getLoadPerSite(int site){
        IntList partitions = PlanHandler.getPartitionsSite(site);
        double load = 0;
        for (int partition : partitions){
            load += getLoadPerPartition(partition);
        }
        return load;
    }

    public double getLoadVertices(IntSet vertices){

        //        DEBUG.clear();

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
            if (fromPartition == -1){
                fromPartition = AffinityGraph.m_vertexPartition.get(vertex);
            }
            else{
                if(fromPartition != AffinityGraph.m_vertexPartition.get(vertex)){
                    int otherPartition = AffinityGraph.m_vertexPartition.get(vertex);
                    ////System.out.println("vertex with hash " + vertex + " and name " + AffinityGraph.m_vertexName.get(vertex) + " is not on partition " + fromPartition + " but on partition " + otherPartition);
                    ////System.out.println("Vertex is in PartitionVertex for partition " + fromPartition + ": " + AffinityGraph.m_partitionVertices.get(fromPartition).contains(vertex));
                    ////System.out.println("Vertex is in PartitionVertex for partition " + otherPartition + ": " + AffinityGraph.m_partitionVertices.get(otherPartition).contains(vertex));
                    System.exit(0);
                }
            }

            int fromSite = PlanHandler.getSitePartition(fromPartition);

            Int2DoubleMap adjacencyList = AffinityGraph.m_edges.get(vertex);
            if(adjacencyList != null){

                for(Int2DoubleMap.Entry edge : adjacencyList.int2DoubleEntrySet()){

                    int toVertex = edge.getIntKey();
                    double edgeWeight = edge.getDoubleValue();

                    int toPartition = AffinityGraph.m_vertexPartition.get(toVertex);

                    if (toPartition != fromPartition){

                        int toSite = PlanHandler.getSitePartition(toPartition);
                        double h = (fromSite == toSite) ? Controller.LMPT_COST : Controller.DTXN_COST;
                        load += edgeWeight * h;
                    }
                }
            }
        }
        return load;
    }

    public double getLoadPerPartition(int partition){

        if (!AffinityGraph.isActive(partition)){
            return 0;
        }

        return getLoadVertices(AffinityGraph.m_partitionVertices.get(partition));
    }

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
                                double k = toPartitionLocal ? Controller.LMPT_COST : Controller.DTXN_COST;
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


    public int getLeastLoadedPartition(IntList activePartitions){
        double minLoad = Double.MAX_VALUE;
        int res = 0;
        for (int part : activePartitions){
            double newLoad = getLoadPerPartition(part);
            if (newLoad < minLoad){
                res = part;
                minLoad = newLoad;
            }
        }
        return res;
    }

    /**
     * Returns sorted (descending order) list of top-k vertices from site
     *
     * @param partition
     * @param k
     * @return
     */
    protected IntList getHottestVertices(int partition, int k){

        IntList res = new IntArrayList (k);
        final Int2DoubleMap hotnessMap = new Int2DoubleOpenHashMap (k);

        k = Math.min(k, AffinityGraph.m_partitionVertices.get(partition).size());
        int lowestPos = 0;
        double lowestLoad = Double.MAX_VALUE;

        IntSet singleton = new IntOpenHashSet(1);

        for(int vertex : AffinityGraph.m_partitionVertices.get(partition)){

            singleton.clear();
            singleton.add(vertex);
            double vertexLoad = getLoadVertices(singleton);

            if (res.size() < k){

                res.add(vertex);
                hotnessMap.put(vertex, vertexLoad);
                if (lowestLoad > vertexLoad){
                    lowestPos = res.size() - 1;
                    lowestLoad = vertexLoad;
                }
            }

            else{
                if(vertexLoad > lowestLoad){

                    hotnessMap.remove(res.get(lowestPos));

                    res.set(lowestPos, vertex);
                    hotnessMap.put(vertex, vertexLoad);

                    // find new lowest load
                    lowestLoad = vertexLoad;
                    for (int i = 0; i < k; i++){
                        double currLoad = hotnessMap.get(res.get(i));
                        if(currLoad < lowestLoad){
                            lowestPos = i;
                            lowestLoad = currLoad;
                        }
                    }
                }
            }
        }

        // sort determines an _ascending_ order
        // Comparator should return "a negative integer, zero, or a positive integer as the first argument is less than, equal to, or greater than the second"
        // We want a _descending_ order, so we need to invert the comparator result
        Collections.sort(res, new AbstractIntComparator(){
            @Override
            public int compare(int o1, int o2) {
                if (hotnessMap.get(o1) < hotnessMap.get(o2)){
                    return 1;
                }
                else if (hotnessMap.get(o1) > hotnessMap.get(o2)){
                    return -1;
                }
                return 0;
            }
        });

        return res;
    }


}
