package org.qcri.affinityplanner;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.UnknownHostException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Iterator;
import java.util.TreeSet;

import org.apache.log4j.Logger;
import org.voltdb.CatalogContext;
import org.voltdb.catalog.Catalog;
import org.voltdb.catalog.Partition;
import org.voltdb.catalog.Site;
import org.voltdb.client.ClientFactory;
import org.voltdb.client.ClientResponse;
import org.voltdb.client.NoConnectionsException;
import org.voltdb.client.ProcCallException;
import org.voltdb.processtools.ShellTools;

import edu.brown.catalog.CatalogUtil;
import edu.brown.hstore.HStoreConstants;
import edu.brown.hstore.Hstoreservice.Status;
import edu.brown.hstore.conf.HStoreConf;
import edu.brown.utils.ArgumentsParser;
import edu.brown.utils.CollectionUtil;
import edu.brown.utils.FileUtil;

public class Controller extends Thread {
    private org.voltdb.client.Client m_client;
    private Collection<Site> m_sites;
    private CatalogContext m_catalog_context;
    private String m_connectedHost;
    
//    private static final Logger LOG = Logger.getLogger(Controller.class);

//    private Path planFile;
//    private Path outputPlanFile;
    

    // Controller
    public static int MAX_PARTITIONS;
    public static int PARTITIONS_PER_SITE;

    public static int MONITORING_TIME = 20000;
    public static boolean EXEC_MONITORING = true;
    public static boolean EXEC_UPDATE_PLAN = true;
    public static boolean EXEC_RECONF = true;
    public static boolean FIRST_RECONF = true;
    public static String PLAN_IN = "plan_affinity.json";
    public static String PLAN_OUT = "plan_out.json";
    public static String METIS_OUT = "metis.txt";
    public static String METIS_MAP_OUT = "metismap.txt";
    
    
    public static String ALGO = "default";
    
    // Loader
    public static int LOAD_THREADS = 1;
 
    // Repartitioning
    public static double MIN_LOAD_PER_PART = Double.MIN_VALUE;
    public static double MAX_LOAD_PER_PART = Double.MAX_VALUE;
    public static double IMBALANCE_LOAD = 0;
    public static double LMPT_COST = 1.1;
    public static double DTXN_COST = 50.0;
    public static int MAX_MOVED_TUPLES_PER_PART = Integer.MAX_VALUE;
    public static int MIN_SENDER_GAIN_MOVE = 0;
    public static int MAX_PARTITIONS_ADDED = 6;
    public static double PENALTY_REMOTE_MOVE = 0;
    public static int GREEDY_STEPS_AHEAD = 5;
    public static double LOCAL_AFFINITY_THRESHOLD = Integer.MAX_VALUE; // for graph algorithms: if no local edge is this affine, pick remote edge
    public static int ADDED_PARTITION_CHUNK_SIZE = 6;
    
    public static int COLD_CHUNK_SIZE = 100;
    public static double COLD_TUPLE_FRACTION_ACCESSES = 100;
    public static int TOPK = Integer.MAX_VALUE;
    
    public static String ROOT_TABLE = null;
   
    public Controller (Catalog catalog, HStoreConf hstore_conf, CatalogContext catalog_context) {
        
        if(EXEC_MONITORING || EXEC_RECONF){
            m_client = ClientFactory.createClient();
            m_client.configureBlocking(false);
        }
        m_sites = CatalogUtil.getAllSites(catalog);
        m_catalog_context = catalog_context;
        
        TreeSet<Integer> partitionIds = new TreeSet<Integer>();
        MAX_PARTITIONS = -1;
        PARTITIONS_PER_SITE = -1;
        for(Site site: m_sites){
            for (Partition part: site.getPartitions()){
                int id = part.getId();
                partitionIds.add(id);
                if (id > MAX_PARTITIONS){
                    MAX_PARTITIONS = id;
                }
            }
            // verify that all sites have the same number of partitions
            if (PARTITIONS_PER_SITE == -1){
                PARTITIONS_PER_SITE = site.getPartitions().size();
            }
            else if (PARTITIONS_PER_SITE != site.getPartitions().size()){
                record("Not all sites have the same number of partitions. Exiting");
                System.exit(1);
            }
        }
        MAX_PARTITIONS++;

        if (hstore_conf.global.hasher_plan != null) {
            PLAN_IN = hstore_conf.global.hasher_plan;
//            LOG.info("Updating plan_in to be " + PLAN_IN);
        }
        else{
            System.out.println("No plan specified. Exiting");
            System.exit(1);
        }
        
        // verify that all partition ids are contiguous
        Iterator<Integer> partIdsIt = partitionIds.iterator();
        int lastPart = partIdsIt.next();
        while (partIdsIt.hasNext()){
            int currPart = partIdsIt.next();
            if (currPart == (lastPart + 1)){
                lastPart = currPart;
            }
            else{
                record("Gap in partition ids. Exiting");
                System.exit(1);
            }
        }
    }
    
    public static void record(String s){
        System.out.println(s);
        FileUtil.appendEventToFile(s);        
    }
    
    public void run (){
        // turn monitoring on and off
        if(EXEC_MONITORING || EXEC_RECONF){
            connectToHost();
        }
        
        long t1;
        long t2;

        File planFile = new File (PLAN_IN);
        Path[] logFiles = new Path[MAX_PARTITIONS];
        Path[] intervalFiles = new Path[MAX_PARTITIONS];
        for (int i = 0; i < MAX_PARTITIONS; i++){
            logFiles[i] = FileSystems.getDefault().getPath(".", "transactions-partition-" + i + ".log");
            intervalFiles[i] = FileSystems.getDefault().getPath(".", "interval-partition-" + i + ".log");
        }

        if(EXEC_MONITORING){
            record("================== RUNNING MONITORING ======================");

            String[] confNames = {"site.access_tracking"};
            String[] confValues = {"true"};
            @SuppressWarnings("unused")
            ClientResponse cresponse;
            try {
                cresponse = m_client.callProcedure("@SetConfiguration", confNames, confValues);
            } catch (IOException | ProcCallException e) {
                record("Problem while turning on monitoring");
                record(stackTraceToString(e));
                System.exit(1);
            }
            try {
                Thread.sleep(MONITORING_TIME);
            } catch (InterruptedException e) {
                record("sleeping interrupted while monitoring");
                System.exit(1);
            }
            confValues[0] = "false";
            try {
                cresponse = m_client.callProcedure("@SetConfiguration", confNames, confValues);
            } catch (IOException | ProcCallException e) {
                record("Problem while turning off");
                record(stackTraceToString(e));
                System.exit(1);
            }

            record("================== FETCHING MONITORING FILES ======================");
            t1 = System.currentTimeMillis();
            String hStoreDir = ShellTools.cmd("pwd");
            hStoreDir = hStoreDir.replaceAll("(\\r|\\n)", "");
            String command = "python scripts/partitioning/fetch_monitor.py " + hStoreDir;
            for(Site site: m_sites){
                command = command + " " + site.getHost().getIpaddr();
            }
            @SuppressWarnings("unused")
            String results = ShellTools.cmd(command);
            
            t2 = System.currentTimeMillis();
            record("Time taken:" + (t2-t1));

        } // END if(RUN_MONITORING)
        if(EXEC_UPDATE_PLAN){
            
            record("======================== LOADING GRAPH ========================");
            t1 = System.currentTimeMillis();
            
            String hStoreDir = ShellTools.cmd("pwd");
            hStoreDir = hStoreDir.replaceAll("(\\r|\\n)", "");
            if (ROOT_TABLE != null){
                System.out.println("Got the root table " + ROOT_TABLE);
                String command = "python scripts/partitioning/filter_root_table.py " + ROOT_TABLE;
                String results = ShellTools.cmd(command);
            }
    
            Partitioner partitioner = null;
            System.out.println("Algorithm " + ALGO);
            
            // checks parameter -Delastic.algo
            switch(ALGO){
                case "simple":
                    partitioner = new SimplePartitioner(m_catalog_context, planFile, logFiles, intervalFiles);
                    break;
                case "default":
                case "graph":
                    partitioner = new GraphGreedy(m_catalog_context, planFile, logFiles, intervalFiles);
                    break;
                case "graph-ext":
                    partitioner = new GraphGreedyExtended(m_catalog_context, planFile, logFiles, intervalFiles);
                    break;
                case "greedy-ext":
                    partitioner = new GreedyExtended(m_catalog_context, planFile, logFiles, intervalFiles);
                    break;
                case "metis":
                    partitioner = new MetisPartitioner(m_catalog_context, planFile, logFiles, intervalFiles);
                    break;
                default:
                    System.out.println("The name of the specificed partitioner is incorrect. Exiting.");
                    System.exit(1);
            }

            t2 = System.currentTimeMillis();
            record("Time taken: " + (t2-t1));

            if (partitioner instanceof PartitionerAffinity){
                System.out.println("Number of vertices " + ((PartitionerAffinity) partitioner).numVertices());
                System.out.println("Number of edges " + ((PartitionerAffinity) partitioner).numEdges());
//                ((PartitionerAffinity) partitioner).graphToFile(FileSystems.getDefault().getPath(".", "graph.log"));
//                ((PartitionerAffinity) partitioner).graphToCPLEXFile(FileSystems.getDefault().getPath(".", "graph-cplex.txt"));
            }
            
            record("======================== PARTITIONING GRAPH ========================");
            t1 = System.currentTimeMillis();
            
            // detect overloaded and active partitions
            IntList activePartitions = new IntArrayList(Controller.MAX_PARTITIONS);

            System.out.println("Load per partition before reconfiguration");
            double sum = 0;
            for(int i = 0; i < Controller.MAX_PARTITIONS; i++){
                double load = partitioner.getLoadPerPartition(i);
                sum += load;
                if(load > 0){
                    activePartitions.add(i);
                    System.out.println(i + ": " + load);
                }
            }
            
            if(Controller.IMBALANCE_LOAD != 0){
                double avg = sum / activePartitions.size();
                Controller.MAX_LOAD_PER_PART = avg * (1 + Controller.IMBALANCE_LOAD);
                Controller.MIN_LOAD_PER_PART = Math.max(0.0, avg * (1 - Controller.IMBALANCE_LOAD));
            }
            
            System.out.println("Max load: " + Controller.MAX_LOAD_PER_PART);
            System.out.println("Min load: " + Controller.MIN_LOAD_PER_PART);

            boolean b = partitioner.repartition(FIRST_RECONF);
            
            if (!b){
                record("Problem while partitioning graph. Writing incomplete plan out");
            }
            t2 = System.currentTimeMillis();
            record("Time taken:" + (t2-t1));

            partitioner.writePlan(PLAN_OUT);
 
//            String outputPlan = FileUtil.readFile(PLAN_OUT);
//            record("Output plan\n" + outputPlan);
            
            record("Load per partition after reconfiguration");
            for (int j = 0; j < Controller.MAX_PARTITIONS; j++){
                record(j + " " + partitioner.getLoadPerPartition(j));
            }

//            record("Partitioner tuples to move: " + Controller.MAX_MOVED_TUPLES_PER_PART);
            
            System.out.println("Verifying output plan");
            PlanHandler outputPlan = new PlanHandler(new File(PLAN_OUT), m_catalog_context);
            outputPlan.verifyPlan();

            System.out.println("Printing movements");
            PlanHandler inputPlan = new PlanHandler(new File(PLAN_IN), m_catalog_context);
            inputPlan.printDataMovementsTo(outputPlan);
            
//            if (partitioner instanceof PartitionerAffinity){
//                ((PartitionerAffinity) partitioner).graphToFileMPT(FileSystems.getDefault().getPath(".", "mpt.log"));
//            }
            
            
        } // END if(UPDATE_PLAN)

        if(EXEC_RECONF){

            record("======================== STARTING RECONFIGURATION ========================");
            ClientResponse cresponse = null;
            try {
                // TODO should compare with PLAN_IN before reconfiguring
                String outputPlan = FileUtil.readFile(PLAN_OUT);
                cresponse = m_client.callProcedure("@ReconfigurationRemote", 0, outputPlan, "livepull");
                                //cresponse = client.callProcedure("@ReconfigurationRemote", 0, outputPlan, "stopcopy");
                                record("Controller: received response: " + cresponse);
            } catch (NoConnectionsException e) {
                record("Controller: lost connection");
                e.printStackTrace();
                System.exit(1);
            } catch (IOException e) {
                record("Controller: IO Exception while connecting to host");
                e.printStackTrace();
                System.exit(1);
            } catch (ProcCallException e) {
                System.out.println("Controller: @Reconfiguration transaction rejected (backpressure?)");
                e.printStackTrace();
                System.exit(1);
            }

            if(cresponse.getStatus() != Status.OK){
                record("@Reconfiguration transaction aborted");
                System.exit(1);
            }
        } // END if(EXEC_RECONF)

        
    }
    
    public void connectToHost(){
        Site catalog_site = CollectionUtil.random(m_sites);
        m_connectedHost= catalog_site.getHost().getIpaddr();

        try {
            m_client.createConnection(null, m_connectedHost, HStoreConstants.DEFAULT_PORT, "user", "password");
        } catch (UnknownHostException e) {
            record("Controller: tried to connect to unknown host");
            e.printStackTrace();
            System.exit(1);
        } catch (IOException e) {
            record("Controller: IO Exception while connecting to host");
            e.printStackTrace();
            System.exit(1);
        }
        record("Connected to host " + m_connectedHost);
    }
    
    /**
     * @param vargs
     */
    public static void main(String[] vargs){
        
        if(vargs.length == 0){
            record("Must specify server hostname");
            return;
        }       

        ArgumentsParser args = null;
        try {
            args = ArgumentsParser.load(vargs,ArgumentsParser.PARAM_CATALOG);
        } catch (Exception e) {
            record("Problem while parsing Controller arguments");
            record(stackTraceToString(e));
            return;
        }
        
        HStoreConf hstore_conf = HStoreConf.initArgumentsParser(args);

        //System.out.println("Params: bench"+params[0] +" no. part " +vargs[1] + " twin "+vargs[2]+" plannerID "+vargs[3]);
        record("args:: "+args.toString());

        record("vargs.length: "+vargs.length);

        Controller c = new Controller(args.catalog, hstore_conf, args.catalogContext);
        try {
            c.run();
        } catch (Exception e) {
            e.printStackTrace();
            record("Not good");
            return;
        }
    }
    
    public static String stackTraceToString(Throwable e){
        // alternatively can write StdErr to file
        // System.setErr(new PrintStream("error.log"));
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
