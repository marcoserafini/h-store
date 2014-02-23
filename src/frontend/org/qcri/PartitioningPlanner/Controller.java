
package org.qcri.PartitioningPlanner;

import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.HashMap;


import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileSystems;
import java.nio.file.CopyOption;
import java.nio.file.StandardCopyOption;

import org.qcri.PartitioningPlanner.placement.Placement;
import org.qcri.PartitioningPlanner.placement.GreedyPlacement;
import org.qcri.PartitioningPlanner.placement.GreedyExtendedPlacement;
import org.qcri.PartitioningPlanner.placement.BinPackerPlacement;
import org.qcri.PartitioningPlanner.placement.FirstFitPlacement;
import org.qcri.PartitioningPlanner.placement.OneTieredPlacement;
import org.qcri.PartitioningPlanner.placement.GAPlacement;
import org.qcri.PartitioningPlanner.placement.Plan;
import org.voltdb.client.ClientFactory;
import org.voltdb.client.ClientResponse;
import org.voltdb.client.NoConnectionsException;
import org.voltdb.client.ProcCallException;
import org.voltdb.catalog.Catalog;
import org.voltdb.catalog.Site;
import org.voltdb.processtools.ShellTools;

import edu.brown.catalog.CatalogUtil;
import edu.brown.hstore.HStoreConstants;
import edu.brown.hstore.Hstoreservice.Status;
import edu.brown.hstore.conf.HStoreConf;
import edu.brown.utils.ArgumentsParser;
import edu.brown.utils.CollectionUtil;

public class Controller implements Runnable {

	private org.voltdb.client.Client client;
	private String connectedHost;
	private Collection<Site> sites;

	private Placement algo;
	private Plan currentPlan;
	private Path planFile;
	private Path outputPlanFile;


	private TupleTrackerExecutor ttExecutor;
	private Provisioning provisioning;

	private static final int POLL_FREQUENCY = 3000;
	private static String HSTORE_HOME = "~/h-store";

	private static int time_window = 10; // time window for tuple tracking

	private static int planner_selector = 0; // planner ID from 0 to 4, Greedy - GreedyEx - FFit - BP - BP one tier 

	private static int no_of_partitions = 4; 
	private static int doProvisioning = 0;
	private static int timeLimit = 60000; // 1 minute
	private static int doMonitoring = 0;
	private static int sitesPerHost = 1;
	private static int partPerSite = 1;
	private static double highCPU = 160;
	private static double lowCPU = 110;


	// used HStoreTerminal as model to handle the catalog

	public Controller (Catalog catalog, HStoreConf hstore_conf){

		ttExecutor = new TupleTrackerExecutor();
		// connect to VoltDB server
		client = ClientFactory.createClient();
		client.configureBlocking(false);
		sites = CatalogUtil.getAllSites(catalog);
		connectToHost();
		provisioning = new Provisioning(sites, no_of_partitions, sitesPerHost, partPerSite, highCPU, lowCPU);

		if(hstore_conf.global.hasher_plan == null){
			System.out.println("Must set global.hasher_plan to specify plan file!");
			System.out.println("Using default (plan.json)");
			planFile = FileSystems.getDefault().getPath("plan.json");

		}
		else{
			planFile = FileSystems.getDefault().getPath(hstore_conf.global.hasher_plan);
		}

		outputPlanFile = FileSystems.getDefault().getPath("plan_out.json");

		try {
			Files.copy(planFile, outputPlanFile, StandardCopyOption.REPLACE_EXISTING);				
		}
		catch(IOException e) {
			System.out.println("Controller: IO Exception while copying plan file to output plan file");
			e.printStackTrace();
		}

		switch (planner_selector) {
		case 0:  algo = new GreedyPlacement(); System.out.println("GreedyPlacement is selected"); break;
		case 1:  algo = new GreedyExtendedPlacement(); System.out.println("GreedyExtendedPlacement is selected"); break;
		case 2:  algo = new FirstFitPlacement(); System.out.println("FirstFitPlacement is selected"); break;
		case 3:  algo = new BinPackerPlacement(); System.out.println("BinPackerPlacement is selected"); break;
		case 4:  algo = new OneTieredPlacement(); System.out.println("OneTieredPlacement is selected"); break;
		case 5:  algo = new GAPlacement(); System.out.println("GAPlacement is selected"); break;
		}

	}

	@Override
	public void run () {
		if (doMonitoring == 1){
			try {
				while(true){
					Thread.sleep(POLL_FREQUENCY);
					System.out.println("\nPolling");
					if(!provisioning.needReconfiguration()) continue;
					System.out.println("Starting reconfiguration");
					doReconfiguration();
					System.out.println("Waiting until reconfiguration has completed");
					String ip = sites.iterator().next().getHost().getIpaddr();
					String response = ShellTools.cmd("ssh " + ip + " grep RECONFIGURATION_END " + HSTORE_HOME + "/hevent.log");
					int previousReconfigurations = response.split("\n").length; 
					while(true){
						Thread.sleep(1000);
						response = ShellTools.cmd("ssh " + ip + " grep RECONFIGURATION_END " + HSTORE_HOME + "/hevent.log");
						if(response.split("\n").length > previousReconfigurations) break;
					}
					System.out.println("Reconfiguration has completed");
					provisioning.refreshCPUStats();
				}
			} catch (InterruptedException e) {
				System.out.println("Controller was interrupted");
				e.printStackTrace();
				System.exit(1);
			}
		}
		else{
			doReconfiguration();
		}
	}

	public void doReconfiguration(){

		//Jennie temp for now
		Map<Integer, Long> mSiteLoad = new HashMap<Integer, Long>();

		ArrayList<Map<Long, Long>> hotTuplesList = new ArrayList<Map<Long, Long>> (no_of_partitions);




		try {

			//ttExecutor.runTestCase(); 	
			//System.out.println("Essam Before: hotTuplesList size is " + hotTuplesList.size());
			System.out.println("Starting tuple tracking");	

			ttExecutor.turnOnOff(time_window,client);	// turn on tuple tracking for time window of X seconds

			
			
			// here we get top K
			ttExecutor.getTopKPerPart(no_of_partitions,hotTuplesList);

			// here we get load per site
			ttExecutor.getSiteLoadPerPart(no_of_partitions,mSiteLoad);

			System.out.println("Got list of hot tuples");	

			// here we call the planner
			// @todo - last parameter should be the number of partitions in use - may be less than
			// hotTuplesList.size()

			if(doProvisioning == 1)
			{

				System.out.println("Provisioning is on");	
				int numberOfPartitions = provisioning.partitionsRequired();
				System.out.println("Provisioning requires " + numberOfPartitions + " partitions");
				currentPlan = algo.computePlan(hotTuplesList, mSiteLoad, planFile.toString(), 
						numberOfPartitions, timeLimit);
				provisioning.setPartitions(numberOfPartitions);

			}
			else
			{
				System.out.println("Provisioning is off");
				currentPlan = algo.computePlan(hotTuplesList, mSiteLoad, planFile.toString(), 
						no_of_partitions, timeLimit);
			}

			System.out.println("Calculated new plan");

			currentPlan.toJSON(outputPlanFile.toString());


			ClientResponse cresponse = null;
			try {
				cresponse = client.callProcedure("@Reconfiguration", 0, outputPlanFile.toString(), "livepull");
				//cresponse = client.callProcedure("@Reconfiguration", 0, outputPlanFile.toString(), "stopcopy");
				System.out.println("Controller: received response: " + cresponse);
			} catch (NoConnectionsException e) {
				System.out.println("Controller: lost connection");
				e.printStackTrace();
				System.exit(1);
			} catch (IOException e) {
				System.out.println("Controller: IO Exception while connecting to host");
				e.printStackTrace();
				System.exit(1);
			} catch (ProcCallException e) {
				System.out.println("Controller: @Reconfiguration transaction rejected (backpressure?)");
				e.printStackTrace();
				System.exit(1);
			}

			if(cresponse.getStatus() != Status.OK){
				System.out.println("@Reconfiguration transaction aborted");
				System.exit(1);
			}

		} catch(Exception e) {
			System.out.println("Caught on exception " + e.toString());
		}
	}

	public void connectToHost(){
		Site catalog_site = CollectionUtil.random(sites);
		connectedHost= catalog_site.getHost().getIpaddr();

		try {
			client.createConnection(null, connectedHost, HStoreConstants.DEFAULT_PORT, "user", "password");
		} catch (UnknownHostException e) {
			System.out.println("Controller: tried to connect to unknown host");
			e.printStackTrace();
			System.exit(1);
		} catch (IOException e) {
			System.out.println("Controller: IO Exception while connecting to host");
			e.printStackTrace();
			System.exit(1);
		}
		System.out.println("Connected to host " + connectedHost);
	}




	/**
	 * @param args
	 */
	public static void main(String[] vargs) throws Exception{

		if(vargs.length == 0){
			System.out.println("Must specify server hostname");
			return;
		}		

		ArgumentsParser args = ArgumentsParser.load(vargs,ArgumentsParser.PARAM_CATALOG);

		HStoreConf hstore_conf = HStoreConf.initArgumentsParser(args);

		//System.out.println("Params: bench"+params[0] +" no. part " +vargs[1] + " twin "+vargs[2]+" plannerID "+vargs[3]);
		System.out.println("args:: "+args.toString());

		System.out.println("vargs.length: "+vargs.length);

		if(vargs.length > 1)
		{
			no_of_partitions = Integer.parseInt(vargs[1]);
			time_window = Integer.parseInt(vargs[2]);
			planner_selector = Integer.parseInt(vargs[3]);
			doProvisioning = Integer.parseInt(vargs[4]);
			timeLimit = Integer.parseInt(vargs[5]);
			doMonitoring = Integer.parseInt(vargs[6]);
			sitesPerHost = Integer.parseInt(vargs[7]);
			partPerSite = Integer.parseInt(vargs[8]);
			highCPU = Double.parseDouble(vargs[9]);
			lowCPU = Double.parseDouble(vargs[10]);
		}
		else // use default
		{
			System.out.println("Using default parameters");
			no_of_partitions = 4;
			time_window = 10;
			planner_selector = 0;
			doProvisioning = 0;
			timeLimit = 60000;
			doMonitoring = 0;
			sitesPerHost = 1;
			partPerSite = 1;
			highCPU = 1280;
			lowCPU = 960;
		}


		Controller c = new Controller(args.catalog, hstore_conf);
		c.run();
	}
}
