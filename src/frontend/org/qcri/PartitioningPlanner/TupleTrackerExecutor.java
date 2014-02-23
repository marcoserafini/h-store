package org.qcri.PartitioningPlanner;

//import java.util.Collection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.io.*;

import org.voltdb.VoltTable;
//import org.voltdb.VoltTableRow;
//import org.voltdb.client.ClientConfig;
//import org.voltdb.client.Client;
//import org.voltdb.client.ClientFactory;
import org.voltdb.client.ClientResponse;

public class TupleTrackerExecutor {
	//Collection<Site> allSites;
	//Map<Site,Collection<Integer>> siteToPartitions;	
	//Map<Integer, Site> partitionToSite;
	
	// hotTuples: tupleId --> access count
	// siteLoads: partitionId --> total access count
 	
		
	public TupleTrackerExecutor(){
		
	}
	
	
	public void runTestCase() throws Exception {
		
		int port = 21212;
		String host = "localhost";
		String statsType = "TUPLE";
		int interval = 0;
		
		//ClientConfig clientConfig = new ClientConfig("program", "none");
        org.voltdb.client.Client client =
            org.voltdb.client.ClientFactory.createClient();
        
        // Client instance connected to the database running on
        // the specified IP address, in this case 127.0.0.1. The
        // database always runs on TCP/IP port 21212.
        client.createConnection(host,port);
		
		String query = "SELECT COUNT(*) FROM WAREHOUSE WHERE W_ID = 1";
		ClientResponse cresponse = client.callProcedure("@AdHoc", query);
		VoltTable[] count = cresponse.getResults(); 
		System.out.printf("Found WAREHOUSE no %d.\n", count[0].fetchRow(0).getLong(0));
		
		ClientResponse results = client.callProcedure("@Statistics", statsType, interval);
		
		
		
	}
	
	
	
public void turnOnOff(int seconds, org.voltdb.client.Client client) throws Exception {
		
		String statsType = "TUPLE";
		int interval = 0;
		
		//ClientConfig clientConfig = new ClientConfig("program", "none");
        //org.voltdb.client.Client client =   org.voltdb.client.ClientFactory.createClient();
        
        // Client instance connected to the database running on
        // the specified IP address, in this case 127.0.0.1. The
        // database always runs on TCP/IP port 21212.
        //client.createConnection(host,port);
        
        //System.out.println("Tuple Tracking: Connected host");
		
		//ClientResponse results = client.callProcedure("@Statistics", statsType, interval);
        
		//interval = 1; //turn on the tracking
        //client.callProcedure("@Statistics", statsType, interval);
        //System.out.println("Tuple Tracking has been turned on for "+seconds+" seconds");
        
		//turning on 
        String confNames[] = { "site.exec_readwrite_tracking"};
        String confValuesOn[] = {"true"};
        String confValuesOff[] = {"false"};
        client.callProcedure("@SetConfiguration", confNames, confValuesOn);
        		
		try
		  {
		  Thread.sleep(seconds*1000);  
		 
		  }catch (InterruptedException ie)
		  {
		  System.out.println(ie.getMessage());
		  }
		
		
		client.callProcedure("@Statistics", statsType, interval);
		
		client.callProcedure("@SetConfiguration", confNames, confValuesOff);
		
		System.out.println("Tuple Tracking collectted hot tuples and has been turned off");
		
	}
   
	public void getTopKPerPart(int noPartitions, ArrayList<Map<Long, Long>> htList) throws Exception {
		
		
		
		
		
		
		Map<Long, Long> hotTuples;
		BufferedReader reader;
		String fNPrefix ="./hotTuplesPID_";
		String line;
		
		
		
		for (int i = 0; i < noPartitions; i++) {
			
			reader = new BufferedReader(new FileReader(fNPrefix+i+".del"));
			hotTuples = new HashMap<Long, Long>();
			line = reader.readLine(); // escape first line
			
			while ((line = reader.readLine()) != null) {
	            String parts[] = line.split("\t");
	            hotTuples.put(Long.parseLong(parts[1]), Long.parseLong(parts[2]));
	        }
			reader.close();
			htList.add(hotTuples);
			
			//System.out.println("Essam hotTuplesList at partition"+i+" has " + hotTuples.size()); 
		    
		}
		
		
	   
	   
		
	}
	
	
    public void getSiteLoadPerPart(int noPartitions, Map<Integer, Long> mSLoad) throws Exception  {
    	
    	BufferedReader reader;
		String fNPrefix ="./siteLoadPID_";
		String line;
		
		
		
		for (int i = 0; i < noPartitions; i++) {
			
			reader = new BufferedReader(new FileReader(fNPrefix+i+".del"));
			if ((line = reader.readLine()) != null) {
	            String parts[] = line.split("\t");
	            mSLoad.put(Integer.parseInt(parts[0]), Long.parseLong(parts[1]));
	        }
			reader.close();
			
		    
		}
	   
	   
		
	}
	
	
	
	
}
