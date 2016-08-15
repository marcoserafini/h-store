package org.qcri.affinityplanner;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.log4j.Logger;
import org.voltdb.CatalogContext;

import it.unimi.dsi.fastutil.ints.Int2DoubleMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntSet;

public class MetisPartitioner extends GraphGreedy {

    private static final Logger LOG = Logger.getLogger(MetisPartitioner.class);
    
    private int m_partitionsNo;

    public MetisPartitioner (CatalogContext catalogContext, File planFile, Path[] logFiles, Path[] intervalFiles){
        super(catalogContext, planFile, logFiles, intervalFiles);
        
        m_partitionsNo = Controller.MAX_PARTITIONS;
    }

    @Override
    public boolean repartition(boolean firstRepartition) {
        
        System.out.println(String.format("Writing metis graph out to %s ",FileSystems.getDefault().getPath(".", Controller.METIS_OUT)));

        Path metisFile = FileSystems.getDefault().getPath(".", Controller.METIS_OUT);
        Path metisMapFile = FileSystems.getDefault().getPath(".", Controller.METIS_MAP_OUT);

        long start = System.currentTimeMillis();
        m_graph.toMetisFile(metisFile, metisMapFile);

        long time = System.currentTimeMillis() - start;
        System.out.println("generating metis out file took : " + time);
        Path metisOut= FileSystems.getDefault().getPath(".", Controller.METIS_OUT + ".part." + m_partitionsNo); 
        
        //RESULTS map of hashID -> new partition ID
        Int2IntOpenHashMap metisGeneratedPartitioning = null;
        try {
            start = System.currentTimeMillis();

            Process metisProc = null;
            if (Controller.IMBALANCE_LOAD == 0){
                metisProc = new ProcessBuilder("gpmetis", "-ptype=rb", Controller.METIS_OUT, ""+ m_partitionsNo).start();
                System.out.println("Calling gpmetis -ptype=rb " + Controller.METIS_OUT + " "+ m_partitionsNo);
            }
            else{
//                double imbalance = (1 + Controller.IMBALANCE_LOAD) * 1000 - 1;
                double imbalance = Controller.IMBALANCE_LOAD * 1000;
                metisProc = new ProcessBuilder("gpmetis", "-ptype=rb", "-ufactor=" + imbalance, Controller.METIS_OUT, ""+ m_partitionsNo).start();                
                System.out.println("Calling gpmetis -ptype=rb -ufactor=" + imbalance + " " + Controller.METIS_OUT + " " + m_partitionsNo);
            }

            // LOG.info("metis proc: " + metisProc.toString());
            int result = metisProc.waitFor();
            time = System.currentTimeMillis() - start;

            if (result == 0){
                System.out.println(String.format("Metis ran successfully. took : " + time));

                metisGeneratedPartitioning = getMetisMapping(metisOut, metisMapFile);
                System.out.println("Results in metis map files: " + metisGeneratedPartitioning.keySet().size());

                m_graph.movesToMultipleReceivers(metisGeneratedPartitioning);
            
            } else {
                System.out.println(String.format("Metis ran unsuccessfully: %s", result));
                return false;
            }
            
        } catch (Exception e) {
            LOG.error("Exception running metis", e);
            return false;
        }
        
        return true;
    }

    private Int2IntOpenHashMap getMetisMapping(Path metisOut, Path metisMapFile) {
        
        System.out.println(String.format("Getting metis out and mapping to hashes for %s and %s", metisOut, metisMapFile));
        Int2IntOpenHashMap res = new Int2IntOpenHashMap();
        BufferedReader outReader, metisMapReader;
        String outPart, hashId;

        try {
        
            outReader = Files.newBufferedReader(metisOut, Charset.forName("US-ASCII"));
            metisMapReader = Files.newBufferedReader(metisMapFile, Charset.forName("US-ASCII"));
            
            while(true){
                
                outPart = outReader.readLine();
                hashId = metisMapReader.readLine();
                
                if (outPart == null && hashId == null){
                    break;
                } else if (outPart == null && hashId != null){
                    LOG.error("Ran out of hashes before partition maps...");
                    break;
                } else if (outPart == null && hashId == null){
                    LOG.error("Ran out of partition maps before hashes...");
                    break;
                }
                
                res.put(Integer.parseInt(hashId), Integer.parseInt(outPart));
            }

            
        } catch (IOException e) {
            Controller.record("Error while reading out files \n Stack trace:\n" + Controller.stackTraceToString(e));
        }
        return res;
    }

}
