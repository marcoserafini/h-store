package org.qcri.PartitioningPlanner;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.voltdb.VoltTable;
import org.voltdb.VoltTableRow;
import org.voltdb.catalog.CatalogMap;
import org.voltdb.catalog.Partition;
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
	private HashMap <Integer,Integer> executorPIDs = new HashMap<Integer,Integer>();

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
		for(Site site : sites){
		     String ip = site.getHost().getIpaddr();
		    for(Partition part : site.getPartitions()){
                String command = "ssh -t -t %s top -n1 | grep -m1 java | perl -pe 's/\\e\\[?.*?[\\@-~] ? ?//g' 2> /dev/null | cut -f1 -d' '";
                String pid = ShellTools.cmd(command);
                // in two steps since the % of printf confuses String.format
                String jstack = String.format("$(jstack %s | grep -m1 \"H%02d-%03d\" | cut -d '=' -f 4 | cut -d ' ' -f 1)", pid, site.getId(), part.getId()); 
                command = "ssh -t -t " + ip + "printf '%d' " + jstack;
		        String result = ShellTools.cmd(command);
		        executorPIDs.put(part.getId(), Integer.parseInt(result));
		    }
		}
	}

	public boolean needReconfiguration(Map<Site,Map<Partition,Double>> CPUUtilPerPartitionMap){
		boolean res = false;
		int partitionsRequired = partitionsRequired(CPUUtilPerPartitionMap);
		for(Site site : CPUUtilPerPartitionMap.keySet()){
			if(site.getId() < (usedPartitions/PARTITIONS_PER_SITE)){
				System.out.println("Polling site " + site.getId() + " with IP " + site.getHost().getIpaddr());
				Map<Partition,Double> CPUUtilPerPartition = CPUUtilPerPartitionMap.get(site);
				for(Map.Entry<Partition,Double> cpu : CPUUtilPerPartition.entrySet()){
					double utilization = cpu.getValue();
					System.out.println("Partition " + cpu.getKey().getId() + " has utilization " + utilization);
					if ((utilization < CPU_THRESHOLD_DOWN && partitionsRequired < usedPartitions) || utilization > CPU_THRESHOLD_UP){
						System.out.println("Need to reconfigure");	 					
						res = true;
					}
				}
			}
		}
		return res;
	}
	
	public Map<Site,Map<Partition,Double>> getCPUUtilPerPartition() {
		Map<Site,Map<Partition,Double>> CPUUtilPerPartitionMap = new HashMap<>();
		for(Site site : sites){
			if(site.getId() < (usedPartitions/PARTITIONS_PER_SITE)){
				Map<Partition,Double> CPUUtilPerPartition = getCPUPerPartitionSsh(site);
				CPUUtilPerPartitionMap.put(site, CPUUtilPerPartition);
			}
		}
		return CPUUtilPerPartitionMap;
	}

	// ======== NOTE!! =================
	// assumes that partitions 0-7 go to site 0, 8-15 to site 1 and so on
	//
	public int partitionsRequired(Map<Site,Map<Partition,Double>> CPUUtilPerPartitionMap){
		double totalUtil = 0;

		for(Site site : CPUUtilPerPartitionMap.keySet()){
			if(site.getId() < (usedPartitions/PARTITIONS_PER_SITE)){
				Map<Partition,Double> CPUUtilPerPartition = CPUUtilPerPartitionMap.get(site);
				for(Double util : CPUUtilPerPartition.values()){
					totalUtil += util;
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
			getCPUPerPartitionSsh(site);
		}
	}


	// ======== THE FOLLOWING METHODS USE SSH AND PS TO GET CPU UTILIZATION =================

	public Map<Partition,Double> getCPUPerPartitionSsh(Site site){
		String ip = site.getHost().getIpaddr();
		CatalogMap<Partition> partitions = site.getPartitions();
		HashMap<Partition,Double> res = new HashMap<Partition,Double>();
        String command = String.format("ssh -t -t " + ip + " top -H -n1 | grep java");
        String result = ShellTools.cmd(command);
		for(Partition part : partitions){
//			System.out.format("Polling site %d and partition %d with ip %s", site.getId(), part.getId(), ip);
			// TODO very system specific. it should parse all "lines" entries and find the one with "java"
			String[] lines = result.split("\n");
			String[] fields = null;
			for (int i = 0; i < lines.length; i++){
			    if(lines[i].indexOf(executorPIDs.get(part.getId())) != -1){
			        fields = lines[i].split("\\s+");
			        break;
			    }
			}
			if(fields == null){
			    System.out.println("top has no entry for partition id " + part.getId() + " and PID " + executorPIDs.get(part.getId()));
			}
            try{
                    res.put(part, Double.parseDouble(fields[8]));
            } catch(NumberFormatException e){
            	//sometimes the first field is an empty string so the cpu utilization is not number 8
            	//this is because of top
                    try{
                            res.put(part, Double.parseDouble(fields[9]));
                    }
                    catch(NumberFormatException e1){
                            System.out.println("Problem reading CPU utilization from ip " + ip);
                            System.out.println("top returned the following response. It could not be parsed");
                            System.out.println(result);
                            e1.printStackTrace();
                            System.exit(-1);
                    }
            }
		}
		return res;
	}

	//	public static int noOfSitesRequiredSSH(Collection<Site> sites, int usedSites){
	//	double cpuUsage = 0;
	//	for(Site site : sites){
	//		if(site.getId() % SITES_PER_HOST == 0 && site.getId() < usedSites){
	//			String ip = site.getHost().getIpaddr();
	//			cpuUsage += getCPUSsh(ip);
	//		}
	//	}
	//	if ((cpuUsage / usedSites) > CPU_THRESHOLD_UP){
	//		// currently adds only one server at a time
	//		return usedSites + SITES_PER_HOST;
	//	}
	//	if ((cpuUsage / usedSites) < CPU_THRESHOLD_DOWN){
	//		return usedSites - SITES_PER_HOST;
	//	}
	//	return usedSites;
	//}

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

