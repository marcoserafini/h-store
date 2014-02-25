package org.qcri.PartitioningPlanner;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;

import org.voltdb.VoltTable;
import org.voltdb.VoltTableRow;
import org.voltdb.catalog.Site;
import org.voltdb.client.Client;
import org.voltdb.client.ClientResponse;
import org.voltdb.client.NoConnectionsException;
import org.voltdb.client.ProcCallException;
import org.voltdb.processtools.ShellTools;

import edu.brown.hstore.Hstoreservice.Status;

public class Provisioning {
	private final double CPU_THRESHOLD_UP;
	private final double CPU_THRESHOLD_DOWN;
	private final int SITES_PER_HOST;
	private final int PARTITIONS_PER_SITE;
	private final int MAX_SITES;
	private Collection<Site> sites;

	double avgCPUUtil;
	private int usedPartitions;

	public Provisioning(Collection<Site> sites, int initialPartitions, int sitesPerHost, int partitionsPerSite, double highCPU, double lowCPU){
		this.sites = sites;
		this.CPU_THRESHOLD_UP = highCPU;
		this.CPU_THRESHOLD_DOWN = lowCPU;
		this.SITES_PER_HOST = sitesPerHost;
		this.PARTITIONS_PER_SITE = partitionsPerSite;
		this.MAX_SITES = sites.size();
		this.usedPartitions = initialPartitions; 
	}

	public boolean needReconfiguration(){
		for(Site site : sites){
			if(site.getId() < (usedPartitions/PARTITIONS_PER_SITE)){
				System.out.println("Polling site " + site.getId() + " with IP " + site.getHost().getIpaddr());
				double[] CPUUtilPerPartition = getCPUPerPartitionSsh(site.getHost().getIpaddr(), PARTITIONS_PER_SITE);
				for(int i = 0; i <  CPUUtilPerPartition.length; i++){
					System.out.println("Examining partition " + i + " with load " + CPUUtilPerPartition[i]);
					if ((CPUUtilPerPartition[i] < CPU_THRESHOLD_DOWN && partitionsRequired() < usedPartitions) || CPUUtilPerPartition[i] > CPU_THRESHOLD_UP){
						System.out.println("Need to reconfigure");	 					
						return true;
					}
				}
			}
		}
		return false;
	}

	// ======== NOTE!! =================
	// assumes that site 0-7 go to server 0, 8-15 to server 2 and so on
	// and one partition per server
	//
	public int partitionsRequired(){
		double totalUtil = 0;

		for(Site site : sites){
			if(site.getId() < (usedPartitions/PARTITIONS_PER_SITE)){
				double[] CPUUtilPerPartition = getCPUPerPartitionSsh(site.getHost().getIpaddr(), PARTITIONS_PER_SITE);
				for(int i = 0; i <  CPUUtilPerPartition.length; i++){
					totalUtil += CPUUtilPerPartition[i];
				}
			}
		}
		
		double avgUtilPerPartition = totalUtil/usedPartitions;

		if(avgUtilPerPartition > CPU_THRESHOLD_UP){
			double newPartitions = usedPartitions + Math.ceil((avgUtilPerPartition - CPU_THRESHOLD_UP) * usedPartitions);
			int newSites =(int) Math.ceil(newPartitions / PARTITIONS_PER_SITE);
			// round up considering site per host
			if (newSites % SITES_PER_HOST != 0){
				newSites += SITES_PER_HOST - (newSites % SITES_PER_HOST);
			}
			return Math.min(newSites, MAX_SITES) * PARTITIONS_PER_SITE;
		}
		else if(avgUtilPerPartition < CPU_THRESHOLD_DOWN){
			double newPartitions = Math.max(usedPartitions - Math.ceil((CPU_THRESHOLD_DOWN - avgUtilPerPartition) * usedPartitions), PARTITIONS_PER_SITE);
			int newSites = (int) Math.ceil(newPartitions / PARTITIONS_PER_SITE);
			// round up considering site per host
			if (newSites % SITES_PER_HOST != 0){
				newSites += SITES_PER_HOST - (newSites % SITES_PER_HOST);
			}
			return Math.max(newSites, 1) * PARTITIONS_PER_SITE;
		}
//		System.out.println("Provisioning returns " + usedSites + " sites");
		return usedPartitions;
	}
	
	public void setPartitions(int partitions){
		usedPartitions = partitions;
	}

	public void refreshCPUStats(){
		for(Site site : sites){
			getCPUPerPartitionSsh(site.getHost().getIpaddr(), PARTITIONS_PER_SITE);
		}
	}

	
// ======== THE FOLLOWING METHODS USE SSH AND PS TO GET CPU UTILIZATION =================
	
//	public static int noOfSitesRequiredSSH(Collection<Site> sites, int usedSites){
//		double cpuUsage = 0;
//		for(Site site : sites){
//			if(site.getId() % SITES_PER_HOST == 0 && site.getId() < usedSites){
//				String ip = site.getHost().getIpaddr();
//				cpuUsage += getCPUSsh(ip);
//			}
//		}
//		if ((cpuUsage / usedSites) > CPU_THRESHOLD_UP){
//			// currently adds only one server at a time
//			return usedSites + SITES_PER_HOST;
//		}
//		if ((cpuUsage / usedSites) < CPU_THRESHOLD_DOWN){
//			return usedSites - SITES_PER_HOST;
//		}
//		return usedSites;
//	}
	
	public static double getCPUPerSiteSsh(String ip){
		System.out.println("Polling " + ip);
		String results = ShellTools.cmd("ssh " + ip + " ps -eo pcpu");
		String[] lines = results.split("\n");
		if (lines.length < 2){
			System.out.println("Controller: Problem while polling CPU usage for host " + ip);
			System.exit(-1);
		}
		double cpuUsage = 0;
		for (int i = 1; i < lines.length; ++i){
			try{
				cpuUsage += Double.parseDouble(lines[i]);
			} catch(NumberFormatException e){
				System.out.println("Problem reading CPU utilization from ip " + ip);
				System.out.println("ps returned the following response. It could not be parsed");
				System.out.println(results);
				e.printStackTrace();
				System.exit(-1);
			}
		}
		System.out.println("CPU usage of host " + ip + ": " + cpuUsage);
		return cpuUsage;
	}

	// assumes that the numPartitions java threads with the highest CPU utilization are the ones associated with partition execution 
	// alternative would be to monitor CPU utilization per core (e.g. /proc/stat or top and type 1), or the CPU load, but then we should make sure that we do core pinning correctly
	public static double[] getCPUPerPartitionSsh(String ip, int numPartitions){
		double[] res = new double [numPartitions];
//		System.out.println("Polling " + ip);
		String results = ShellTools.cmd("ssh " + ip + " top -H -b -n 1 | grep java | head -n " + numPartitions);
		// TODO: test the following to avoid the ugly fix. the problem is related with using top and cut together http://nurkiewicz.blogspot.com/2012/08/which-java-thread-consumes-my-cpu.html
//		String results = ShellTools.cmd("ssh " + ip + " top -H -b -n 1 | grep -m " + numPartitions + " java | perl -pe 's/\\e\\[?.*?[\\@-~] ?//g'  | cut -d' ' -f 14");
		String[] lines = results.split("\n");
		if (lines.length < numPartitions){
			System.out.println("Controller: Problem while polling CPU usage for host " + ip);
			System.out.println("Returned string: " + results);
			System.exit(-1);
		}
//		System.out.println("CPU usage of host " + ip + ":");
		for (int i = 0; i < lines.length; ++i){
			//TODO should simply to the following (with todo above)
			//res[i] = Double.parseDouble(lines[i]);
			String[] fields = lines[i].split("\\s+");
			try{
				res[i] = Double.parseDouble(fields[8]);
//				System.out.println("Partition " + i + " " + res[i]);
			} catch(NumberFormatException e){
				// sometimes the first field is an empty string so the cpu utilization is not number 8 
				// this is a dirty fix but it's good enough for now - see the TODO above
				try{
					res[i] = Double.parseDouble(fields[9]);
				}
				catch(NumberFormatException e1){
					System.out.println("Problem reading CPU utilization from ip " + ip);
					System.out.println("top returned the following response. It could not be parsed");
					System.out.println(results);
					e1.printStackTrace();
					System.exit(-1);
				}
			}
		}
		return res;
	}
	
	// ======== THE FOLLOWING METHODS USE CPUSTATS TO GET CPU UTILIZATION *PER SITE* =================

	private HashMap<Integer, Double> queryCPUUtilPerHost(Client client, int usedSites){
		System.out.println("Used sites: " + usedSites);
		ClientResponse cresponse = doStatsQuery(client, "CPUUSAGE");
		HashMap<Integer,Double> res = new HashMap<Integer,Double>();
		try{
			Thread.sleep(3000);
		} catch (InterruptedException e) {}
		cresponse = doStatsQuery(client, "CPUUSAGE");
		VoltTable stats = cresponse.getResults()[0];
		for(int r = 0; r < stats.getRowCount(); ++r){
			VoltTableRow row = stats.fetchRow(r);
			System.out.println("Examining site " +  row.getString(2));
			int site = (int) row.getLong(1);
			int host = site / SITES_PER_HOST;
			System.out.println("Host is " + host);
			double utilization = row.getDouble(4);
			System.out.println("Utilization is " + utilization);
			res.put(host,utilization);
		}
		return res;
	}

	// general method to execute queries
	public static ClientResponse doStatsQuery(Client client, String statsType){
		int interval = 0;
		ClientResponse cresponse = null;
		try {
			cresponse = client.callProcedure("@Statistics", statsType, interval);
		} catch (NoConnectionsException e) {
			System.out.println("Controller: lost connection");
			e.printStackTrace();
			System.exit(1);
				
		} catch (IOException e) {
			System.out.println("Controller: IO Exception while connecting to host");
			e.printStackTrace();
			System.exit(1);
		} catch (ProcCallException e) {
			System.out.println("Controller: @Statistics transaction rejected (backpressure?)");
			e.printStackTrace();
			System.exit(1);
		}
		if(cresponse.getStatus() != Status.OK){
			System.out.println("@Statistics transaction aborted");
			System.exit(1);
		}
		return cresponse;
	}
	
// ====================================================================================================
	
	// TODO not used yet. could be used to tune the CPU threshold dynamically based on SLAs
	public static boolean queryLatencies(Client client){
		//		if(connectedHost == null){
		//			connectToHost();
		//		}
		ClientResponse cresponse = doStatsQuery(client, "TXNRESPONSETIME");

		System.out.println("Received stats, parsing...");
		VoltTable stats = cresponse.getResults()[0];

		String prevHost = stats.fetchRow(0).getString(2);
		long goodTxns = 0;
		long badTxns = 0;
		for(int r = 0; r < stats.getRowCount(); ++r){
			VoltTableRow row = stats.fetchRow(r);
			String currHost = row.getString(2);
			if (!prevHost.equals(currHost)){
				// finished the rows related to the previous host
				if (badTxns != 0){
					if (goodTxns == 0 || badTxns/goodTxns > 0.05) return false;
				}
				System.out.println("Host " + prevHost +" - good transactions: " + goodTxns + "; bad transactions: " + badTxns);
				goodTxns = 0;
				badTxns = 0;
				prevHost = currHost; 
			}

			goodTxns += row.getLong(4) + row.getLong(5) + row.getLong(6);
			badTxns += row.getLong(7);
			//			System.out.println("Row " + r);
			//			System.out.println("\tPartition: " + row.getLong(1));
			//			System.out.println("\tHost: " + row.getString(2));
			//			System.out.println("\tProcedure: " + row.getString(3));
			//			System.out.println("\tCount-100: " + row.getLong(4));
		}
		if (badTxns != 0){
			if (goodTxns == 0 || badTxns/goodTxns > 0.05) return false;
		}
		System.out.println("Host " + prevHost +" - good transactions: " + goodTxns + "; bad transactions: " + badTxns);
		return true;
	}

}

