package edu.brown.hstore.reconfiguration;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.lang.NotImplementedException;
import org.apache.log4j.Logger;
import org.jfree.util.Log;
import org.voltdb.VoltTable;
import org.voltdb.VoltType;
import org.voltdb.catalog.CatalogType;
import org.voltdb.exceptions.ReconfigurationException.ExceptionTypes;
import org.voltdb.messaging.FastDeserializer;
import org.voltdb.messaging.FastSerializer;
import org.voltdb.utils.Pair;

import com.google.protobuf.ByteString;
import com.google.protobuf.RpcCallback;

import edu.brown.hashing.TwoTieredRangeHasher;
import edu.brown.hashing.TwoTieredRangePartitions;
import edu.brown.hashing.ReconfigurationPlan;
import edu.brown.hashing.ReconfigurationPlan.ReconfigurationRange;
import edu.brown.hstore.HStoreSite;
import edu.brown.hstore.Hstoreservice.AsyncPullRequest;
import edu.brown.hstore.Hstoreservice.AsyncPullResponse;
import edu.brown.hstore.Hstoreservice.DataTransferRequest;
import edu.brown.hstore.Hstoreservice.DataTransferResponse;
import edu.brown.hstore.Hstoreservice.HStoreService;
import edu.brown.hstore.Hstoreservice.LivePullRequest;
import edu.brown.hstore.Hstoreservice.LivePullResponse;
import edu.brown.hstore.Hstoreservice.MultiPullReplyRequest;
import edu.brown.hstore.Hstoreservice.MultiPullReplyResponse;
import edu.brown.hstore.Hstoreservice.ReconfigurationControlRequest;
import edu.brown.hstore.Hstoreservice.ReconfigurationControlType;
import edu.brown.hstore.Hstoreservice.ReconfigurationRequest;
import edu.brown.hstore.Hstoreservice.ReconfigurationResponse;
import edu.brown.hstore.PartitionExecutor;
import edu.brown.hstore.conf.HStoreConf;
import edu.brown.hstore.internal.AsyncDataPullRequestMessage;
import edu.brown.hstore.internal.AsyncDataPullResponseMessage;
import edu.brown.hstore.internal.MultiDataPullResponseMessage;
import edu.brown.hstore.reconfiguration.ReconfigurationConstants.ReconfigurationProtocols;
import edu.brown.interfaces.Shutdownable;
import edu.brown.logging.LoggerUtil;
import edu.brown.logging.LoggerUtil.LoggerBoolean;
import edu.brown.profilers.ProfileMeasurement;
import edu.brown.profilers.ReconfigurationProfiler;
import edu.brown.protorpc.ProtoRpcController;
import edu.brown.statistics.FastIntHistogram;
import edu.brown.utils.FileUtil;
import edu.brown.utils.PartitionSet;

/**
 * @author vaibhav : Reconfiguration Coordinator at each site, responsible for
 *         maintaining reconfiguration state and sending communication messages
 */
/**
 * @author aelmore
 */
public class ReconfigurationCoordinator implements Shutdownable {
    private static final Logger LOG = Logger.getLogger(ReconfigurationCoordinator.class);
    private static final LoggerBoolean debug = new LoggerBoolean(LOG.isDebugEnabled());
    private static final LoggerBoolean trace = new LoggerBoolean(LOG.isTraceEnabled());
    public static final boolean STATIC_PULL_FILTER = true;
    public static boolean detailed_timing = true;
    private static boolean async_nonchunk_push = false;
    private static boolean async_nonchunk_pull = false;
    private static boolean async_pull_immediately_in_work_queue = false;
    private static boolean async_queue_pulls = false;
    private static boolean live_pull = true;
    private static boolean abortsEnabledForStopCopy = true;
    
    // Cached list of local executors
    private List<PartitionExecutor> local_executors;
    static {
        LoggerUtil.setupLogging();
        LoggerUtil.attachObserver(LOG, debug, trace);
    }

    public enum ReconfigurationState {
        NORMAL, BEGIN, PREPARE, DATA_TRANSFER, BULK_TRANSFER, END
    }

    private HStoreSite hstore_site;

    private ReconfigurationState reconfigurationState;
    // Hostname of the reconfiguration leader site
    private Integer reconfigurationLeader;
    private AtomicBoolean reconfigurationInProgress;
    private ReconfigurationPlan currentReconfigurationPlan;
    private ReconfigurationProtocols reconfigurationProtocol;
    private String currentPartitionPlan;
    private int localSiteId;
    private HStoreService channels[];
    private Set<Integer> destinationsReady;
    private int destinationSize;

    // used for generating unique IDs
    private int rcRequestId;
    // Map of partitions in a reconfiguration and their state. No entry is not
    // in reconfiguration;
    private Map<Integer, ReconfigurationState> partitionStates;
    private Map<Integer, ReconfigurationState> initialPartitionStates;

    // map of requests a PE is blocked on
    private Map<Integer, Semaphore> blockedRequests;
    private TwoTieredRangePartitions planned_partitions;
    public ReconfigurationProfiler profilers[];
    private HStoreConf hstore_conf;
    
    private Set<Integer> reconfigurationDonePartitionIds;
    private Set<Integer> reconfigurationDoneSites;
    private int num_of_sites;
    private int num_sites_complete;
    private Set<Integer> sites_complete;
    
    //some tracking
    private Map<Integer,Long> dataPullResponseTimes;
    private Map<Integer,PartitionExecutor>  executorMap;
    private Map<Integer,Integer>  livePullKBMap;
    
    public static long STOP_COPY_TXNID = -2L;

    public ReconfigurationCoordinator(HStoreSite hstore_site, HStoreConf hstore_conf) {        
        if (FORCE_DESTINATION){
            LOG.warn("***********\n FORCE PULL IS ON \n *********");
        }
        this.reconfigurationLeader = -1;
        this.reconfigurationInProgress = new AtomicBoolean(false);
        this.currentReconfigurationPlan = null;
        this.reconfigurationState = ReconfigurationState.NORMAL;
        this.hstore_site = hstore_site;
        this.local_executors = new ArrayList<>();
        this.channels = hstore_site.getCoordinator().getChannels();
        this.partitionStates = new ConcurrentHashMap<Integer, ReconfigurationCoordinator.ReconfigurationState>();
        this.hstore_conf = hstore_conf;
        executorMap = new HashMap<>();

        int num_partitions = hstore_site.getCatalogContext().numberOfPartitions;
        this.num_of_sites = hstore_site.getCatalogContext().numberOfSites;
        if(hstore_conf.site.reconfig_profiling) 
            this.profilers = new ReconfigurationProfiler[num_partitions];
        for (int p_id : hstore_site.getLocalPartitionIds().values()) {
            this.local_executors.add(hstore_site.getPartitionExecutor(p_id));
            executorMap.put(p_id,hstore_site.getPartitionExecutor(p_id));
            this.partitionStates.put(p_id, ReconfigurationState.NORMAL);
            if(hstore_conf.site.reconfig_profiling) 
                this.profilers[p_id] = new ReconfigurationProfiler();
        }
        this.initialPartitionStates = Collections.unmodifiableMap(partitionStates);
        this.localSiteId = hstore_site.getSiteId();
        this.blockedRequests = new ConcurrentHashMap<>();
        this.rcRequestId = 1;
        this.reconfigurationDonePartitionIds = new HashSet<Integer>();
        reconfigurationDoneSites = new HashSet<Integer>();
        
        dataPullResponseTimes = new HashMap<>(); 
        
        detailed_timing = hstore_conf.site.reconfig_detailed_profiling;
        
        async_nonchunk_push = hstore_conf.site.reconfig_async_nonchunk_push;
        async_nonchunk_pull = hstore_conf.site.reconfig_async_nonchunk_pull;
        async_pull_immediately_in_work_queue =false;        
        async_queue_pulls =  hstore_conf.site.reconfig_async_pull;
        live_pull = hstore_conf.site.reconfig_live;

        if (hstore_conf.site.reconfig_async == true){
            LOG.debug("Updating transfer bytes");
            ReconfigurationConstants.MAX_TRANSFER_BYTES = 
                    Math.min(hstore_conf.site.reconfig_async_chunk_size_kb * 1024, ReconfigurationConstants.MAX_TRANSFER_BYTES) ;
            LOG.info("update transfer bytes to :  " + ReconfigurationConstants.MAX_TRANSFER_BYTES);
        }
        if (hstore_conf.site.reconfig_async == false){ 
          LOG.info("Disabling all async pulls");
          async_nonchunk_push = false;
          async_nonchunk_pull = false;
          async_pull_immediately_in_work_queue = false;
          async_queue_pulls = false;        
        } else if (async_queue_pulls) {
          LOG.info("Using async queue. Disabling other async methods");
          async_pull_immediately_in_work_queue = false;
          async_nonchunk_push = false;
          async_nonchunk_pull = false;          
        } else if (async_pull_immediately_in_work_queue) {
            LOG.debug("Disabling nonchunked push and pull, since chunked pull is enabled");
            async_nonchunk_push = false;
            async_nonchunk_pull = false;
        } else //We only want one to be set
            if (async_nonchunk_pull && async_nonchunk_push) {
                LOG.warn("Async push and pull both set. Disabling async_push");
                async_nonchunk_push = false;
            }
        
        String debugConfig = String.format("Reconfig configuration. DetailedTiming: %s AsyncPush:%s AysncPull:%s AsyncQueuePulls:%s LivePulls:%s AsyncPullSizeKB:%s LivePullSizeKB:%s", 
                detailed_timing, async_nonchunk_push, async_nonchunk_pull, async_queue_pulls, live_pull, hstore_conf.site.reconfig_async_chunk_size_kb, hstore_conf.site.reconfig_chunk_size_kb);
        LOG.info(debugConfig);
        FileUtil.appendEventToFile(debugConfig);
        
    }

    /**
     * Initialize a reconfiguration. May be called by multiple PEs, so first
     * request initializes and caches plan. Additional requests will be given
     * cached plan.
     * 
     * @param leaderId
     * @param reconfigurationProtocol
     * @param partitionPlan
     * @param partitionId
     * @return the reconfiguration plan or null if plan already set
     */
    public ReconfigurationPlan initReconfiguration(Integer leaderId, ReconfigurationProtocols reconfigurationProtocol, String partitionPlanFile, int partitionId) {

        // TODO ae start timing
        if (this.reconfigurationInProgress.get() == false && partitionPlanFile == this.currentPartitionPlan) {
            LOG.info("Ignoring initReconfiguration request. Requested plan is already set");
            return null;
        }
        if (reconfigurationProtocol == ReconfigurationProtocols.STOPCOPY) {
        } else if (reconfigurationProtocol == ReconfigurationProtocols.LIVEPULL) {

        } else {
            throw new NotImplementedException();
        }

        // We may have reconfiguration initialized by PEs so need to ensure
        // atomic
        if (this.reconfigurationInProgress.compareAndSet(false, true)) {
            LOG.info("Initializing reconfiguration. New reconfig plan.");
            livePullKBMap = new HashMap<>();
            for (PartitionExecutor executor : this.local_executors) {
                livePullKBMap.put(executor.getPartitionId(),new Integer(0));
            }
            if (this.hstore_site.getSiteId() == leaderId) {
                // TODO : Check if more leader logic is needed
                FileUtil.appendEventToFile("LEADER_RECONFIG_INIT, siteId="+this.hstore_site.getSiteId());
                if (debug.val) {
                    LOG.debug("Setting site as reconfig leader");
                }
            } else {
            	FileUtil.appendEventToFile("RECONFIG_INIT, siteId="+this.hstore_site.getSiteId());
            }
            this.reconfigurationLeader = leaderId;
            this.reconfigurationProtocol = reconfigurationProtocol;
            this.currentPartitionPlan = partitionPlanFile;
            TwoTieredRangeHasher hasher = (TwoTieredRangeHasher) this.hstore_site.getHasher();
            ReconfigurationPlan reconfig_plan;
            
            //Used by the leader to track the reconfiguration state of each partition and each site respectively 
            this.reconfigurationDonePartitionIds = new HashSet<Integer>();
            reconfigurationDoneSites = new HashSet<Integer>();
            
            try {
                // Find reconfig plan
                reconfig_plan = hasher.changePartitionPlan(partitionPlanFile);
                FileUtil.appendEventToFile(reconfig_plan.planDebug);
                this.planned_partitions = hasher.getPartitions();
                if (reconfigurationProtocol == ReconfigurationProtocols.STOPCOPY) {
                    if (reconfig_plan != null){
                        LOG.info("initReconfig for STOPCOPY");
                        this.partitionStates.put(partitionId, ReconfigurationState.DATA_TRANSFER);
                        this.reconfigurationState = ReconfigurationState.DATA_TRANSFER;
                        long siteStart = System.currentTimeMillis();
                        int siteKBSent = 0;
                        //turn off local executions
                        for (PartitionExecutor executor : this.local_executors) {
                            //TODO hstore_site.getTransactionQueueManager().clearQueues(executor.getPartitionId())
                            executor.initReconfiguration(reconfig_plan, reconfigurationProtocol, ReconfigurationState.PREPARE, this.planned_partitions);
                            this.partitionStates.put(partitionId, ReconfigurationState.PREPARE);
                        }
                        //push outgoing ranges for all local PEs
                        //TODO remove this loop and schedule chunked pulls/ 
                        for (PartitionExecutor executor : this.local_executors) {
                        	LOG.info("Schduling async chunked pulls for local PE for Stop and Copy : " + executor.getPartitionId());
                        	executor.scheduleInitialAsyncPullRequestsForSC(executor.getIncomingRanges());
                            /** Comment the previous S&C work
                              
                            LOG.info("Pushing ranges for local PE : " + executor.getPartitionId());
                            long peStart = System.currentTimeMillis();
                            int kbSent = 0;
                            List<ReconfigurationRange<? extends Comparable<?>>> outgoing_ranges = executor.getOutgoingRanges();
                            if (outgoing_ranges != null && outgoing_ranges.size()>0) {
                                for (ReconfigurationRange<? extends Comparable<?>> range : outgoing_ranges) {
                                    boolean hasMoreData = true;
                                    while(hasMoreData){
                                        Pair<VoltTable,Boolean> res = executor.extractPushRequst(range);
                                        kbSent += res.getFirst().getUnderlyingBufferSize()/1000.0;
                                        pushTuples(range.old_partition, range.new_partition, range.table_name, res.getFirst(), 
                                                range.min_long,  range.max_long);
                                        hasMoreData = res.getSecond();
                                    }
                                }
                            } else {
                                LOG.info("no outgoing ranges for PE : " + executor.getPartitionId());
                            }
                            long peTime = System.currentTimeMillis() - peStart;
                            LOG.info(String.format("STOPCOPY for PE(%s) took %s ms for %s kb", executor.getPartitionId(), peTime, kbSent));
                            siteKBSent += kbSent;
                            
                            **/
                        }
                        // Check here whether S&C has ended
                        // The checking is done by each partition based on whether they have processed
                        // all the scheduled async messages or not
                        boolean reconfigEnds = false;
                        while(!reconfigEnds){
                          reconfigEnds = true;
                          
                          for (PartitionExecutor executor : this.local_executors) {
                            if(executor.getPartitionId() == partitionId){
                              executor.processQueuedLiveReconfigWork(true);
                            }
                            reconfigEnds = reconfigEnds && executor.checkAsyncPullMessageQueue();
                          }
                        }
                        
                        // Halt processing and clear queues at each partition
                        for (PartitionExecutor executor : this.local_executors) {
                        	if(abortsEnabledForStopCopy){
                        		executor.haltProcessing();
                                hstore_site.getTransactionQueueManager().clearQueues(executor.getPartitionId());
                        	}
                        }
                        
                        //TODO this file is horrible and needs refactoring....
                        //we have an end, reset and finish reconfiguration...
                        endReconfiguration();
                        resetReconfigurationInProgress();
                        long siteTime = System.currentTimeMillis() - siteStart;
                        String timeMsg = String.format("STOPCOPY for site %s took %s ms and send %s kb", this.hstore_site.getSiteId(), siteTime, siteKBSent);
                        LOG.info(timeMsg);
                        FileUtil.appendEventToFile(timeMsg);
                    } else {
                        LOG.info("No reconfig plan, nothing to do");
                    }



                } else if (reconfigurationProtocol == ReconfigurationProtocols.LIVEPULL) {
                    if (reconfig_plan != null) {
                        if (this.hstore_site.getSiteId() == leaderId) {
                            this.num_sites_complete = 0;
                            this.sites_complete = new HashSet<Integer>();
                        }
                        for (PartitionExecutor executor : this.local_executors) {
                            executor.initReconfiguration(reconfig_plan, reconfigurationProtocol, ReconfigurationState.PREPARE, this.planned_partitions);
                            this.partitionStates.put(partitionId, ReconfigurationState.PREPARE);
                        }
                        // Notify leader that this node has been initialized /
                        // prepared
                        notifyReconfigLeader(ReconfigurationState.PREPARE);
                    } else {
                        LOG.info("No reconfig plan, nothing to do");
                    }
                } else {
                    throw new NotImplementedException();
                }
            } catch (Exception e) {
                LOG.error("Exception converting plan", e);
                throw new RuntimeException(e);
            }
            this.currentReconfigurationPlan = reconfig_plan;

            LOG.info("Init reconfiguraiton complete");
            return reconfig_plan;
        } else {
            // If the reconfig plan is null, but we are in progress we should
            // re-attempt to get it;
            int tries = 0;
            int max_tries = 20;
            long sleep_time = 50;
            while (this.currentReconfigurationPlan == null && tries < max_tries) {
                try {
                    Thread.sleep(sleep_time);
                    tries++;
                } catch (InterruptedException e) {
                    LOG.error("Error sleeping", e);
                }
            }
            LOG.debug(String.format("Init reconfiguration returning existing plan %s", this.currentReconfigurationPlan));

            LOG.info("Init reconfiguraiton complete");
            return this.currentReconfigurationPlan;
        }
    }
    
    public void reconfigurationSysProcTerminate(){
        LOG.info(" ## ** ReconfigurationSysProcTerminate");
    }

    /**
     * Notify leader of a state change
     * 
     * @param begin
     */
    private void notifyReconfigLeader(ReconfigurationState state) {
        assert (this.reconfigurationLeader != -1) : "No reconfiguration leader set";
        LOG.info("Notifying reconfiguration leader of state:" + state.toString());
        if (this.hstore_site.getSiteId() == this.reconfigurationLeader) {
            // This node is the leader
        } else {
            // TODO send state notification to leader
            
        }

    }

    /**
     * Function called by a PE when its active part of the reconfiguration is
     * complete
     * 
     * @param partitionId
     */
    public void finishReconfiguration(int partitionId) {
        if (this.reconfigurationProtocol == ReconfigurationProtocols.STOPCOPY) {
            this.partitionStates.remove(partitionId);

            if (allPartitionsFinished()) {
                
                LOG.info("Last PE finished reconfiguration for STOPCOPY");
                resetReconfigurationInProgress();
            }
        } else if (this.reconfigurationProtocol == ReconfigurationProtocols.LIVEPULL){
            // send a message to the leader that the reconfiguration is done
            this.reconfigurationDonePartitionIds.add(partitionId);
            LOG.info(" ** Partition has finished : " + partitionId + " " + reconfigurationDonePartitionIds.size() + " / " + this.local_executors.size());
            
            //Check all the partitions are done
            if(this.reconfigurationDonePartitionIds.size() == this.local_executors.size()){
                // signal end of reconfiguration to leader
                signalEndReconfigurationToLeader(this.localSiteId, partitionId);
            }
            
        }

    }
    
    /**
     * Signal the end of reconfiguration for a siteId to the leader
     * @param siteId
     */
    public void signalEndReconfigurationToLeader(int siteId, int callingPartition) {
        LOG.info("Signalling endReconfigToLeader : " + siteId );
        ReconfigurationControlRequest leaderCallback = ReconfigurationControlRequest.newBuilder().setSrcPartition(callingPartition)
                .setDestPartition(this.reconfigurationLeader)
                .setReconfigControlType(ReconfigurationControlType.RECONFIGURATION_DONE)
                .setReceiverSite(this.reconfigurationLeader)
                .setMessageIdentifier(-1).
                setSenderSite(localSiteId).build();
                
       
        //TODO : Can we get away with creating an instance each time
        ProtoRpcController controller = new ProtoRpcController();
        int destinationId = this.hstore_site.getCatalogContext().getSiteIdForPartitionId(this.reconfigurationLeader);

        if (this.channels == null){
        	LOG.error("Communication Channels are null. Can't send "+ReconfigurationControlType.RECONFIGURATION_DONE +" message");
        }
      
        if (destinationId == this.localSiteId){
            leaderLocalSiteReconfigurationComplete(this.localSiteId);
        } else{
        	if (this.channels[destinationId] == null){
        		  LOG.error("Reconfig Leader Channel is null. " +  destinationId + " : " + this.channels);
        	} else{
        		this.channels[destinationId].reconfigurationControlMsg(controller, leaderCallback, null);
        		FileUtil.appendEventToFile("RECONFIGURATION_SITE_DONE, siteId="+this.hstore_site.getSiteId());
                showReconfigurationProfiler(true);
        	}
        }
    }
    
    private void sendReconfigEndAcknowledgementToAllSites(){
    	//Reconfiguration leader sends ack that reconfiguration has been done
    	for(int i = 0;i < num_of_sites;i++){
    		int dummySrcpartition = 0;
    		int dummyDestPartition = 0;
        	ProtoRpcController controller = new ProtoRpcController();
        	ReconfigurationControlRequest reconfigEndAck = ReconfigurationControlRequest.newBuilder()
                    .setSenderSite(this.reconfigurationLeader).setDestPartition(dummyDestPartition)
                    .setSrcPartition(dummySrcpartition)
                    .setReconfigControlType(ReconfigurationControlType.RECONFIGURATION_DONE_RECEIVED)
                    .setReceiverSite(i)
                    .setMessageIdentifier(-1).build();
        	if(i != localSiteId) {
        		this.channels[i].reconfigurationControlMsg(controller, reconfigEndAck, null);
        	} else {
        		receiveReconfigurationCompleteFromLeader();
        	}
        	LOG.info("Sent reconfig end acknowledgement to site " + i);
        }
    }
    
    private void leaderLocalSiteReconfigurationComplete(int siteId){
        LOG.info("Site reconfiguration complete for the leader : " + siteId);
        reconfigurationDoneSites.add(siteId);
        FileUtil.appendEventToFile("LEADER_RECONFIGURATION_SITE_DONE, siteId="+this.hstore_site.getSiteId());

        LOG.info("Reconfiguration is done for the leader");
        //Leader was the last one to complete
        if(reconfigurationDoneSites.size() == num_of_sites){
            LOG.info("All sites have reported that reconfiguration is complete "); 
            LOG.info("Sending a message to notify all sites that reconfiguration has ended");
            FileUtil.appendEventToFile("RECONFIGURATION_" + ReconfigurationState.END.toString()+", siteId="+this.hstore_site.getSiteId());
            sendReconfigEndAcknowledgementToAllSites();
            showReconfigurationProfiler(true);
        }
    }
    
    /**
     * Called for the reconfiguration leader to signify that
     * @siteId is done with reconfiguration
     * @param siteId
     */
    public void leaderReceiveRemoteReconfigComplete(int siteId) {
        if(this.localSiteId != this.reconfigurationLeader){
            LOG.error("This message should only go to reconfiguration leader");
            return;
        } 
        LOG.info("Got a message to end Reconfiguration from site Id :"+ siteId);
        this.reconfigurationDoneSites.add(siteId);
        
        //All sites have checked in, including leader previously
        if(reconfigurationDoneSites.size() == this.hstore_site.getCatalogContext().numberOfSites){
            // Now the leader can be sure that the reconfiguration is done as all sites have checked in
            LOG.info("All sites have reported reconfiguration is complete. " +
                    "Sending a message to notify all sites that reconfiguration has ended");
     
            sendReconfigEndAcknowledgementToAllSites();
            FileUtil.appendEventToFile("RECONFIGURATION_" + ReconfigurationState.END.toString()+" , siteId="+this.hstore_site.getSiteId());
            showReconfigurationProfiler(true);
        }
    }
    
    public void receiveReconfigurationCompleteFromLeader() {
    	LOG.info("Got a message from the reconfiguration leader to end the reconfiguration");
    	endReconfiguration();
    }

    private boolean allPartitionsFinished() {
        for (ReconfigurationState state : partitionStates.values()) {
            if (state != ReconfigurationState.END)
                return false;
        }
        return true;
    }

    private void resetReconfigurationInProgress() {
        this.partitionStates.putAll(this.initialPartitionStates);
        this.currentReconfigurationPlan = null;
        this.reconfigurationLeader = -1;
        this.reconfigurationProtocol = null;
        this.reconfigurationInProgress.set(false);
    }

    /**
     * For live pull protocol move the state to Data Transfer Mode For Stop and
     * Copy, move reconfiguration into Prepare Mode
     */
    public void prepareReconfiguration() {
        if (this.reconfigurationInProgress.get()) {
            if (this.reconfigurationProtocol == ReconfigurationProtocols.LIVEPULL) {
                // Move the reconfiguration state to data transfer and data will
                // be
                // pulled based on
                // demand form the destination
                this.reconfigurationState = ReconfigurationState.DATA_TRANSFER;
            } else if (this.reconfigurationProtocol == ReconfigurationProtocols.STOPCOPY) {
                // First set the state to send control messages
                LOG.info("Preparing STOPCOPY reconfiguration");
                this.reconfigurationState = ReconfigurationState.PREPARE;
                this.sendPrepare(this.findDestinationSites());
            }
        }
    }

    
    public void pushTuples(int oldPartitionId, int newPartitionId, String table_name, VoltTable vt, Long minInclusive, Long maxExclusive) throws Exception {
        List<Long> mins = new ArrayList<>();
        List<Long> maxs = new ArrayList<>();
        mins.add(minInclusive);
        maxs.add(maxExclusive);
        pushTuples(oldPartitionId, newPartitionId, table_name, vt, mins, maxs);
    }
    
    /**
     * @param oldPartitionId
     * @param newPartitionId
     * @param table_name
     * @param vt
     * @throws Exception
     */
    public void pushTuples(int oldPartitionId, int newPartitionId, String table_name, VoltTable vt, List<Long> minInclusive, List<Long> maxExclusive) throws Exception {
        LOG.info(String.format("pushTuples keys (%s-%s] for [%s]  partitions %s->%s", minInclusive, maxExclusive, table_name, oldPartitionId, newPartitionId));
        int destinationId = this.hstore_site.getCatalogContext().getSiteIdForPartitionId(newPartitionId);

        if (destinationId == localSiteId) {
            // Just push the message through local receive Tuples to the PE'S
            receiveTuples(destinationId, System.currentTimeMillis(), oldPartitionId, newPartitionId, table_name, vt, minInclusive, maxExclusive);
            return;
        }

        ProtoRpcController controller = new ProtoRpcController();
        ByteString tableBytes = null;
        try {
            ByteBuffer b = ByteBuffer.wrap(FastSerializer.serialize(vt));
            tableBytes = ByteString.copyFrom(b.array());
        } catch (Exception ex) {
            throw new RuntimeException("Unexpected error when serializing Volt Table", ex);
        }

        DataTransferRequest dataTransferRequest = DataTransferRequest.newBuilder().addAllMinInclusive(minInclusive).addAllMaxExclusive(maxExclusive).setSenderSite(this.localSiteId)
                .setOldPartition(oldPartitionId).setNewPartition(newPartitionId).setVoltTableName(table_name).setT0S(System.currentTimeMillis()).setVoltTableData(tableBytes).build();

        this.channels[destinationId].dataTransfer(controller, dataTransferRequest, dataTransferRequestCallback);
    }

    /**
     * Receive the tuples and send it to EE through PE
     * 
     * @param partitionId
     * @param newPartitionId
     * @param table_name
     * @param vt
     * @throws Exception
     */
    public DataTransferResponse receiveTuples(int sourceId, long sentTimeStamp, int partitionId, int newPartitionId, String table_name, VoltTable vt, List<Long> minInclusive, List<Long> maxExclusive)
            throws Exception {
        LOG.info(String.format("receiveTuples  keys for %s  partIds %s->%s", table_name, partitionId, newPartitionId));

        if (vt == null) {
            LOG.error("Volt Table received is null");
        }

        for (PartitionExecutor executor : this.local_executors) {
            // TODO : check if we can more efficient here
            if (executor.getPartitionId() == newPartitionId) {
                // Transaction Id is not needed to be tracked for Stop and Copy
                // so just set it to 0 for now
                LOG.error("TODO add check for moreData"); //TODO fix this
                boolean moreData = false;
                executor.receiveTuples(0L, partitionId, newPartitionId, table_name, minInclusive, maxExclusive, vt, moreData, false);
            }
        }

        DataTransferResponse response = DataTransferResponse.newBuilder().setNewPartition(newPartitionId).setOldPartition(partitionId).setT0S(sentTimeStamp).setSenderSite(sourceId)
                .setVoltTableName(table_name).addAllMinInclusive(minInclusive).addAllMaxExclusive(maxExclusive).build();

        return response;
    }

    /**
     * Call to send tuples in response to a live pull request
     * 
     * @param senderId
     * @param requestTimestamp
     * @param txnId
     * @param oldPartitionId
     * @param newPartitionId
     * @param table_name
     * @param min_inclusive
     * @param max_exclusive
     * @param livePullResponseCallback
     *            - if its null the request was from a partition on a local site
     * @return
     */
    public void dataPullRequest(LivePullRequest livePullRequest, RpcCallback<LivePullResponse> livePullResponseCallback) {
        LOG.info(String.format("dataPullRequest received livePullId %s  keys %s->%s for %s  partIds %s->%s", livePullRequest.getLivePullIdentifier(), livePullRequest.getMinInclusiveList().toString(), livePullRequest.getMaxExclusiveList().toString(), livePullRequest.getVoltTableName(),
                livePullRequest.getOldPartition(), livePullRequest.getNewPartition()));
        long now=0;
        
        if(detailed_timing){
            now = System.currentTimeMillis();
            dataPullResponseTimes.put(livePullRequest.getLivePullIdentifier(), now);
        }
        VoltTable vt = null;
        for (PartitionExecutor executor : this.local_executors) {
            // TODO : check if we can be more efficient here
            if (executor.getPartitionId() == livePullRequest.getOldPartition()) {
                // Queue the live Pull request to the work queue
                // TODO : Change the input parameters for the senTuples function
                if (debug.val) LOG.debug("Queue the live data Pull Request " + executor.getCurrentExecMode().toString() + " " + executor.toString());
                executor.queueLivePullRequest(livePullRequest, livePullResponseCallback);
            }
        }
        if(detailed_timing){
            this.profilers[livePullRequest.getOldPartition()].src_data_pull_req_init_time.appendTime(now, System.currentTimeMillis());
        }
        if (debug.val) LOG.debug("done with queueing  live pull ");

        // TODO : Remove
        /*
         * ByteString tableBytes = null; try { ByteBuffer b =
         * ByteBuffer.wrap(FastSerializer.serialize(vt)); tableBytes =
         * ByteString.copyFrom(b.array()); } catch (Exception ex) { throw new
         * RuntimeException("Unexpected error when serializing Volt Table", ex);
         * } LivePullResponse livePullResponse =
         * LivePullResponse.newBuilder().setSenderSite(this.localSiteId).
         * setOldPartition(oldPartitionId).setNewPartition(newPartitionId)
         * .setVoltTableName
         * (table_name).setT0S(System.currentTimeMillis()).setVoltTableData
         * (tableBytes)
         * .setMinInclusive(min_inclusive).setMaxExclusive(max_exclusive)
         * .setTransactionID(txnId).build();
         */
        return;
    }
    

        
    
    /**
     * Called when a PE requests RC to initiate a pull from another RC
     * @param nextRequestToken
     * @param txnId
     * @param partitionId
     * @param pullRequests
     */
    public void asyncPullRequestFromPE(int livePullId, long txnId, int callingPartition, List<ReconfigurationRange<? extends Comparable<?>>> pullRequests) {

        for(ReconfigurationRange range : pullRequests){       
            
            asyncPullTuples(livePullId, txnId, range.old_partition, range.new_partition, range.table_name, 
                    range.getMinList(), range.getMaxList(), range.getVt());
        }
        
    }

    /**
     * Non-blocking call to pull reconfiguration ranges. Wrapper for pullRanges
     * 
     * @param callingPartition
     * @param pullRequests
     */
    public void pullRangesNonBlocking(int livePullId, long txnId, int callingPartition, Collection<ReconfigurationRange<? extends Comparable<?>>> pullRequests) {
        pullRanges(livePullId, txnId, callingPartition, pullRequests, null);
    }

    /**
     * A request to pull a list of ranges. Non-null blocking sempaphore
     * indicates PE is blocked on list. Each range is an item in semaphore. Does
     * not return until calls are issued and sempaphore is acquired
     * 
     * @param callingPartition
     * @param pullRequests
     * @param blockingSemaphore
     */
    public void pullRanges(int livePullId, 
            long txnId, int callingPartition, Collection<ReconfigurationRange<? extends Comparable<?>>> pullRequests, Semaphore blockingSemaphore){
        try {
            blockingSemaphore.acquire(pullRequests.size());
        } catch (InterruptedException e) {
            LOG.error("Exception acquiring locks for pull request",e);
        }
        
        for(ReconfigurationRange range : pullRequests){       
            //FIXME change pullTuples to be generic comparable
            
            pullTuples(livePullId, txnId, range.old_partition, range.new_partition, range.table_name, 
                    range.getMinList(), range.getMaxList(), range.getVt());
            blockedRequests.put(livePullId, blockingSemaphore);
            //LOG.error("TODO temp removing sempahore for testing");
            //blockingSemaphore.release();         
        }
    }

    /**
     * Live Pull the tuples for a reconfiguration range by generating a live
     * pull request
     * 
     * @param txnId
     * @param oldPartitionId
     * @param newPartitionId
     * @param table_name
     * @param min_inclusive
     * @param max_exclusive
     * @param voltType
     */
    public void pullTuples(int livePullId, Long txnId, int oldPartitionId, int newPartitionId, String table_name, List<Long> min_inclusive, List<Long> max_exclusive, VoltType voltType) {
        LOG.info(String.format("pullTuples with Live Pull ID %s, keys %s->%s for %s  partIds %s->%s", livePullId, min_inclusive, max_exclusive, table_name, oldPartitionId, newPartitionId));
        // TODO : Check if volt type makes can be used here for generic values
        // or remove it
        int sourceID = this.hstore_site.getCatalogContext().getSiteIdForPartitionId(oldPartitionId);
        LOG.error("TODO after chunking : must check if async pull has been issued and is queued");
        ProtoRpcController controller = new ProtoRpcController();

        LivePullRequest livePullRequest = LivePullRequest.newBuilder().setLivePullIdentifier(livePullId).setSenderSite(this.localSiteId).setTransactionID(txnId).setOldPartition(oldPartitionId)
                .setNewPartition(newPartitionId).setVoltTableName(table_name).addAllMinInclusive(min_inclusive).addAllMaxExclusive(max_exclusive).setT0S(System.currentTimeMillis()).build();

        if (sourceID == localSiteId) {
            LOG.debug("pulling from localsite");
            // Just push the message through local receive Tuples to the PE'S
            // If the callback is null, it shows that the request is from a
            // partition in
            // the local site itself.
            dataPullRequest(livePullRequest, null);
            return;
        }

        this.channels[sourceID].livePull(controller, livePullRequest, livePullRequestCallback);
    }
    
    /**
     * Live Pull the tuples for a reconfiguration range by generating a live
     * pull request
     * 
     * @param txnId
     * @param oldPartitionId
     * @param newPartitionId
     * @param table_name
     * @param min_inclusive
     * @param max_exclusive
     * @param voltType
     */
    public void asyncPullTuples(int livePullId, Long txnId, int oldPartitionId, int newPartitionId, String table_name, List<Long> min_inclusive, List<Long> max_exclusive, VoltType voltType) {
        LOG.info(String.format("pullTuples with async Pull ID %s, keys %s->%s for %s  partIds %s->%s", livePullId, min_inclusive, max_exclusive, table_name, oldPartitionId, newPartitionId));
        int sourceID = this.hstore_site.getCatalogContext().getSiteIdForPartitionId(oldPartitionId);

        ProtoRpcController controller = new ProtoRpcController();

        AsyncPullRequest asyncPullRequest = AsyncPullRequest.newBuilder().setAsyncPullIdentifier(livePullId).setSenderSite(this.localSiteId).setTransactionID(txnId).setOldPartition(oldPartitionId)
                .setNewPartition(newPartitionId).setVoltTableName(table_name).addAllMinInclusive(min_inclusive).addAllMaxExclusive(max_exclusive).setT0S(System.currentTimeMillis()).build();

        if (sourceID == localSiteId) {
            LOG.debug("pulling from localsite");
            // Just push the message through local receive Tuples to the PE'S
            // If the callback is null, it shows that the request is from a
            // partition in
            // the local site itself.
            LOG.info("TODO verify passing callback locally works"); //TODO
            queueAsyncDataPullRequest(asyncPullRequest, asyncPullRequestCallback);
            return;
        }

        this.channels[sourceID].asyncPull(controller, asyncPullRequest, asyncPullRequestCallback); 
    }
    
    
    /**
     * Schedule the pull request locally
     * @param asyncPullRequest
     * @param asyncPullRequestCallback2
     */
    private void queueAsyncDataPullRequest(AsyncPullRequest asyncPullRequest, RpcCallback<AsyncPullResponse> asyncPullRequestCallback2) {
        AsyncDataPullRequestMessage asyncPullRequestMsg = new AsyncDataPullRequestMessage(asyncPullRequest, asyncPullRequestCallback2);
        if(asyncPullRequest.getTransactionID() == STOP_COPY_TXNID){
          // This is a s&c request so set the protocol to stop and copy
          LOG.info("Stop & Copy transaction");
          asyncPullRequestMsg.setProtocol("s&c");
        }
        for (PartitionExecutor executor : this.local_executors) {
            if (executor.getPartitionId() == asyncPullRequest.getOldPartition()) {
                LOG.info("Queue the async data pull request " + asyncPullRequest.toString());
                executor.queueAsyncPullRequest(asyncPullRequestMsg);
            }
        }
    }

    
    /**
     * Called when a RC requests to another RC to initiate a pull
     * @param asyncPullRequest
     * @param asyncPullResponseCallback
     */
    public void asyncPullRequestFromRC(AsyncPullRequest asyncPullRequest, RpcCallback<AsyncPullResponse> asyncPullResponseCallback) {
        queueAsyncDataPullRequest(asyncPullRequest,asyncPullResponseCallback);
        
    }
    
    /**
     * Called when a reply to a pull request comes from another RC
     * @param asyncPullRequest
     * @param asyncPullResponseCallback
     */
    public void processPullReplyFromRC(MultiPullReplyRequest multiPullReplyRequest, 
        RpcCallback<MultiPullReplyResponse> multiPullReplyResponseCallback) {
    	
    	if (debug.val) LOG.debug(String.format("Scheduling chunked pull reply message for partition %s ", multiPullReplyRequest.getNewPartition()));
        for (PartitionExecutor executor : local_executors) {
            if(multiPullReplyRequest.getNewPartition() == executor.getPartitionId()){
                if (debug.val) LOG.debug("Queue the pull response frpm another RC. Is it Async: "+multiPullReplyRequest.getIsAsync());
                if (multiPullReplyRequest.hasVoltTableData()){
                    ByteString resp = multiPullReplyRequest.getVoltTableData();
                    livePullKBMap.put(executor.getPartitionId(), livePullKBMap.get(executor.getPartitionId())+resp.size()/1000);
                }
                
                //LOG.error("TODO add chunk ID to response and add new reconfig control type for asyn pull response received"); //TODO
                if(multiPullReplyRequest.getIsAsync()){
                	MultiDataPullResponseMessage pullResponseMsg = new MultiDataPullResponseMessage(multiPullReplyRequest, multiPullReplyResponseCallback);
                    unblockingPullRequestSemaphore(multiPullReplyRequest.getPullIdentifier(), multiPullReplyRequest.getNewPartition(),
                    		true);
                    executor.queueMultiPullResponse(pullResponseMsg);
                } else {
                	processLivePullReplyFromRC(multiPullReplyRequest, multiPullReplyResponseCallback);
                }                      
            }
        }
    }
    
    /**
     * Process Live pull reply 
     * @param multiPullReplyRequest
     */
    public void processLivePullReplyFromRC(MultiPullReplyRequest multiPullReplyRequest, RpcCallback<MultiPullReplyResponse> multiPullReplyResponseCallback){
    	
        if (debug.val) LOG.debug("Processing pull reply from: "+multiPullReplyRequest.getOldPartition()+" to "+ multiPullReplyRequest.getNewPartition());
    	VoltTable vt = null;
        try {
            vt = FastDeserializer.deserialize(multiPullReplyRequest.getVoltTableData().toByteArray(), VoltTable.class);
        } catch (IOException e) {
            LOG.error("Error in deserializing volt table");
        }
		   	    
		    receiveLivePullTuples(multiPullReplyRequest, multiPullReplyResponseCallback);
		    /*
		    MultiPullReplyResponse multiPullReplyResponse = MultiPullReplyResponse.newBuilder().
     		setIsAsync(multiPullReplyRequest.getIsAsync()).
             setPullIdentifier(multiPullReplyRequest.getPullIdentifier()).
             setSenderSite(this.hstore_site.getSiteId()).  
             setOldPartition(multiPullReplyRequest.getOldPartition()).setNewPartition(multiPullReplyRequest.getNewPartition()).
             setVoltTableName(multiPullReplyRequest.getVoltTableName())
             .setT0S(System.currentTimeMillis()).setMinInclusive(multiPullReplyRequest.getMinInclusive()).
             setMaxExclusive(multiPullReplyRequest.getMaxExclusive())
             .setTransactionID(multiPullReplyRequest.getTransactionID()).setChunkId(multiPullReplyRequest.getChunkId()).build();
            // Send the callback of the reply which should work as reconfiguration control message was working for acknowledging the received chunks 
		    
		    multiPullReplyResponseCallback.run(multiPullReplyResponse);
		    */
    	
    }
    
    public void sendMultiPullReplyRequestFromPE(int remoteSiteId, MultiPullReplyRequest multiPullReplyRequest){
    	Log.info("Sending the multi pull reply request");
    	if(localSiteId != remoteSiteId){
    		ProtoRpcController controller = new ProtoRpcController();
            this.channels[remoteSiteId].multiPullReply(controller, multiPullReplyRequest, multiPullReplyResponseCallback);
    	} else {
    		Log.info("Multi Pull reply is local on siteId " + localSiteId);
    		processPullReplyFromRC(multiPullReplyRequest, multiPullReplyResponseCallback);
    	}
    	
    }
    
    public void receiveLivePullTuples(MultiPullReplyRequest multiPullReplyRequest, RpcCallback<MultiPullReplyResponse> multiPullReplyResponseCallback) {
        
    	int livePullId = multiPullReplyRequest.getPullIdentifier();
    	Long txnId = multiPullReplyRequest.getTransactionID();
    	int oldPartitionId = multiPullReplyRequest.getOldPartition();
    	int newPartitionId =  multiPullReplyRequest.getNewPartition();
    	String table_name = multiPullReplyRequest.getVoltTableName();
    	List<Long> min_inclusive = multiPullReplyRequest.getMinInclusiveList(); 
        List<Long> max_exclusive = multiPullReplyRequest.getMaxExclusiveList(); 
        int chunkId = multiPullReplyRequest.getChunkId();
        
        boolean moreDataNeeded = multiPullReplyRequest.getMoreDataNeeded();
        long start=0, receive=0, done=0;
        if (detailed_timing){
            start = System.currentTimeMillis();
        }
        LOG.info(String.format("Received tuples for pullId:%s chunk:%s txn:%s (%s) (from:%s to:%s) for range, " 
                + "(from:%s to:%s)", livePullId, chunkId, txnId, table_name, newPartitionId, oldPartitionId, min_inclusive,
                max_exclusive));
        for (PartitionExecutor executor : local_executors) {
            // TODO : check if we can more efficient here
            if (executor.getPartitionId() == newPartitionId) {
                try {
                	Log.info("We will enqueue the work to load tuples and do it on the PE thread");
                    //executor.receiveTuples(txnId, oldPartitionId, newPartitionId, table_name, min_inclusive, max_exclusive, voltTable, moreDataNeeded, false);
                	MultiDataPullResponseMessage pullResponseMsg = new MultiDataPullResponseMessage(multiPullReplyRequest, multiPullReplyResponseCallback);
                	executor.queueLivePullReplyResponse(pullResponseMsg);
                    if (debug.val && detailed_timing) {
                        receive = System.currentTimeMillis();
                    }
                    // Unblock the semaphore for a blocking request
                    if (blockedRequests.containsKey(livePullId) && blockedRequests.get(livePullId) != null) {
                        if (moreDataNeeded){
                            if (debug.val) LOG.debug("keeping PE blocked as more data is needed");
                        }
                        else { 
                            if (debug.val) LOG.debug("Releasing on PE semaphore for the pulled request " + livePullId);
                            blockedRequests.get(livePullId).release();
                            if (debug.val && detailed_timing) {
                                done = System.currentTimeMillis()-start;
                                receive-=start;
                                if (debug.val) LOG.debug(String.format("(%s) Receive took: %s Receive + Unblock took :%s",newPartitionId, receive, done));
                            }
                        }
                    }
                } catch (Exception e) {
                    LOG.error("Error in partition executors receiving tuples for their pull " + "request", e);
                }
            }
        }

    }

public void receiveLivePullTuples(int livePullId, Long txnId, int oldPartitionId, int newPartitionId, String table_name, List<Long> min_inclusive, 
		List<Long> max_exclusive, VoltTable voltTable, boolean moreDataNeeded) {
        
        long start=0, receive=0, done=0;
        if (detailed_timing){
            start = System.currentTimeMillis();
        }
        LOG.info(String.format("Received tuples for %s %s (%s) (from:%s to:%s) for range, " + "(from:%s to:%s)", livePullId, txnId, table_name, newPartitionId, oldPartitionId, min_inclusive,
                max_exclusive));
        for (PartitionExecutor executor : local_executors) {
            // TODO : check if we can more efficient here
            if (executor.getPartitionId() == newPartitionId) {
                try {
                	
                    executor.receiveTuples(txnId, oldPartitionId, newPartitionId, table_name, min_inclusive, max_exclusive, voltTable, moreDataNeeded, false);
                    if (detailed_timing) {
                        receive = System.currentTimeMillis();
                    }
                    // Unblock the semaphore for a blocking request
                    if (blockedRequests.containsKey(livePullId) && blockedRequests.get(livePullId) != null) {
                        if (moreDataNeeded){
                            LOG.info("keeping PE blocked as more data is needed");
                        }
                        else { 
                            LOG.info("Unblocking the PE for the pulled request " + livePullId);
                            blockedRequests.get(livePullId).release();
                            if (detailed_timing) {
                                done = System.currentTimeMillis()-start;
                                receive-=start;
                                LOG.info(String.format("(%s) Receive took: %s Receive + Unblock took :%s",newPartitionId, receive, done));
                            }
                        }
                    }
                } catch (Exception e) {
                    LOG.error("Error in partition executors receiving tuples for their pull " + "request", e);
                }
            }
        }

    }

    
    /**
     * Parse the partition plan and figure out the destination sites and
     * populates the destination size
     * 
     * @return
     */
    public ArrayList<Integer> findDestinationSites() {
        ArrayList<Integer> destinationSites = new ArrayList<Integer>();

        // TODO : Populate the destinationSize as well

        return destinationSites;
    }

    /**
     * Send prepare messages to all destination sites for Stop and Copy
     * 
     * @param destinationHostNames
     */
    public void sendPrepare(ArrayList<Integer> destinationSites) {
        if (this.reconfigurationProtocol == ReconfigurationProtocols.STOPCOPY) {
            LOG.info("Send prepare for STOPCOPY");
            for (Integer destinationId : destinationSites) {
                // Send a control message to start the reconfiguration

                ProtoRpcController controller = new ProtoRpcController();
                ReconfigurationRequest reconfigurationRequest = ReconfigurationRequest.newBuilder().setSenderSite
                        (this.localSiteId).setT0S(System.currentTimeMillis()).build();

                this.channels[destinationId].reconfiguration(controller, reconfigurationRequest, this.reconfigurationRequestCallback);
            }
        }
    }

    @Override
    public void prepareShutdown(boolean error) {
        // TODO Auto-generated method stub

    }

    @Override
    public void shutdown() {
        // TODO Auto-generated method stub

    }

    @Override
    public boolean isShuttingDown() {
        // TODO Auto-generated method stub
        return false;
    }

    /**
     * used for bulk transfer during stop and copy
     */
    public void bulkDataTransfer() {
        if (this.reconfigurationProtocol == ReconfigurationProtocols.STOPCOPY) {
            LOG.error("Empty bulk data transfer for STOPCOPY");
            // bulk transfer the table data
            // by calling on transfer for each partition of the site
        }
    }

    public int getNextRequestId() {
        return ++rcRequestId;
    }

    /**
     * Invoked after receiving messages from reconfiguration leader signaling
     * end of reconfiguration
     */
    public void endReconfiguration() {
        this.reconfigurationInProgress.set(false);
        LOG.info("Clearing the reconfiguration state for each partition at the site");
        for (PartitionExecutor executor : this.local_executors) {
            executor.endReconfiguration();
            this.partitionStates.put(executor.getPartitionId(), ReconfigurationState.END);
        }
    }

    private final RpcCallback<ReconfigurationResponse> reconfigurationRequestCallback = new RpcCallback<ReconfigurationResponse>() {
        @Override
        public void run(ReconfigurationResponse msg) {
            int senderId = msg.getSenderSite();
            ReconfigurationCoordinator.this.destinationsReady.add(senderId);
            if (ReconfigurationCoordinator.this.reconfigurationInProgress.get() && ReconfigurationCoordinator.this.reconfigurationState == ReconfigurationState.PREPARE
                    && ReconfigurationCoordinator.this.destinationsReady.size() == ReconfigurationCoordinator.this.destinationSize) {
                ReconfigurationCoordinator.this.reconfigurationState = ReconfigurationState.DATA_TRANSFER;
                // bulk data transfer for stop and copy after each destination
                // is ready
                ReconfigurationCoordinator.this.bulkDataTransfer();
            }
        }
    };

    private final RpcCallback<DataTransferResponse> dataTransferRequestCallback = new RpcCallback<DataTransferResponse>() {
        @Override
        public void run(DataTransferResponse msg) {
            LOG.info("Not used...");
            // TODO : Do the book keeping of received messages
            /*
            int senderId = msg.getSenderSite();
            int oldPartition = msg.getOldPartition();
            int newPartition = msg.getNewPartition();
            long timeStamp = msg.getT0S();
            Long minInclusive = msg.getMinInclusive();
            Long maxExlusive = msg.getMaxExclusive();
            */
            // We can track the data Transfer progress using these fields
        }
    };

    private final RpcCallback<LivePullResponse> livePullRequestCallback = new RpcCallback<LivePullResponse>() {
        @Override
        public void run(LivePullResponse msg) {
            // TODO : Do the book keeping of received messages
            int senderId = msg.getSenderSite();
            long timeStamp = msg.getT0S();
            long txnId = msg.getT0S();
            long pullId = msg.getLivePullIdentifier();
            LOG.info("Received senderId " + senderId + " timestamp " + timeStamp + " Transaction Id " + txnId + " Pull ID:"+pullId);
            VoltTable vt = null;
            try {
                vt = FastDeserializer.deserialize(msg.getVoltTableData().toByteArray(), VoltTable.class);
            } catch (IOException e) {
                LOG.error("Error in deserializing volt table");
            }
            // TODO : change the log status later. Info for testing.
            // LOG.info("Volt table Received in callback is "+vt.toString());

            receiveLivePullTuples(msg.getLivePullIdentifier(), msg.getTransactionID(), msg.getOldPartition(), msg.getNewPartition(), msg.getVoltTableName(), msg.getMinInclusiveList(),
                    msg.getMaxExclusiveList(), vt, msg.getMoreDataNeeded());
            
            // send Acknowledgement 

            ReconfigurationControlRequest acknowledgingCallback = ReconfigurationControlRequest.newBuilder().setSrcPartition(msg.getOldPartition())
                    .setDestPartition(msg.getNewPartition())
                    .setReconfigControlType(ReconfigurationControlType.PULL_RECEIVED)
                    .setReceiverSite(msg.getSenderSite())
                    .setMessageIdentifier(msg.getLivePullIdentifier()).
                    setSenderSite(localSiteId).build();
            
            //TODO : Can we get away with creating an instance each time
            ProtoRpcController controller = new ProtoRpcController();
            channels[msg.getSenderSite()].reconfigurationControlMsg(controller, acknowledgingCallback, null);
        }
    };
    
    /*
     * Handling callbacks for async pulls that were issued
     */
    private final RpcCallback<AsyncPullResponse> asyncPullRequestCallback = new RpcCallback<AsyncPullResponse>() {
        @Override
        public void run(AsyncPullResponse msg) {
        	LOG.info(String.format("Scheduling async pull response for partition %s ", msg.getNewPartition()));
            for (PartitionExecutor executor : local_executors) {
                if(msg.getNewPartition() == executor.getPartitionId()){
                    LOG.info("Queue the pull response");
                    ReconfigurationControlRequest acknowledgingCallback = ReconfigurationControlRequest.newBuilder().setSrcPartition(msg.getOldPartition())
                            .setDestPartition(msg.getNewPartition())
                            .setReconfigControlType(ReconfigurationControlType.PULL_RECEIVED)
                            .setReceiverSite(msg.getSenderSite())
                            .setMessageIdentifier(msg.getAsyncPullIdentifier()).                         
                            setSenderSite(localSiteId).build();    
                    LOG.error("TODO add chunk ID to response and add new reconfig control type for asyn pull response received"); //TODO
                    AsyncDataPullResponseMessage pullResponseMsg = new AsyncDataPullResponseMessage(msg, acknowledgingCallback);
                    unblockingPullRequestSemaphore(msg.getAsyncPullIdentifier(), msg.getNewPartition(),
                    		true);
                    executor.queueAsyncPullResponse(pullResponseMsg);                
                }
            }
        }
    };
    
    private final RpcCallback<MultiPullReplyResponse> multiPullReplyResponseCallback = new RpcCallback<MultiPullReplyResponse>() {
        @Override
        public void run(MultiPullReplyResponse msg) {
        	LOG.info(String.format("Callback for multi pull ID:%s chunkID:%s reply for partition %s isAsync:%s ", msg.getPullIdentifier(), msg.getChunkId(), msg.getOldPartition(),msg.getIsAsync()));
        	if(msg.getIsAsync()){
        		queueAsyncDataRequestMessageToWorkQueue(msg.getOldPartition());  
        	}
        	    	
        }
    };
        

    public void sendAcknowledgement(ReconfigurationControlRequest acknowledgingCallback){
        ProtoRpcController controller = new ProtoRpcController();
        LOG.error("TODO send ack if not local");//TODO
        int receiverId = acknowledgingCallback.getReceiverSite();
        if(localSiteId != receiverId){
        	channels[receiverId].reconfigurationControlMsg(controller, acknowledgingCallback, null); 
        } else {
        	queueAsyncDataRequestMessageToWorkQueue(acknowledgingCallback.getDestPartition());
        }
    
    };
    /**
     * Deletes the tuples associated with the live Pull Id of the request processed before
     * @param request
     */
    public void deleteTuples(ReconfigurationControlRequest request){
        LOG.info("Acknowledgement received by source of the tuple in the specified pull request," +
        		" hence we can delete the associated tuples");
        for (PartitionExecutor executor : local_executors) {
            if(request.getSrcPartition() == executor.getPartitionId()){
                LOG.info("Partition Id is "+ executor.getPartitionId()+" Getting EE to delete the tuples");
                if(executor.getExecutionEngine() != null){
                    executor.getExecutionEngine().updateExtractRequest(request.getMessageIdentifier(), true);
                } else {
                    LOG.error("EE seems to be null here");
                }
                break;
            }
        }
    }
    
    public void queueAsyncDataRequestMessageToWorkQueue(int destPartition){
    	LOG.info("Async pull reply has been received and acknowledged. Now queue the job for extracting next chunk for partition: " + destPartition);
    	for (PartitionExecutor executor : local_executors) {
            if(destPartition == executor.getPartitionId()){
            	// Call PE to get the message from the queue
            	executor.queueAsyncDataRequestMessageToWorkQueue();
            }
    	}
    	
    }
    
    public ReconfigurationState getState() {
        return this.reconfigurationState;
    }

    public Integer getReconfigurationLeader() {
        return this.reconfigurationLeader;
    }

    public ReconfigurationProtocols getReconfigurationProtocol() {
        return this.reconfigurationProtocol;
    }

    public String getCurrentPartitionPlan() {
        return this.currentPartitionPlan;
    }

    public boolean getReconfigurationInProgress() {
        return this.reconfigurationInProgress.get();
    }

    public void notifyAllRanges(int partitionId, ExceptionTypes allRangesMigratedType) {
        LOG.info(" ** NOTIFY " + partitionId + " " + allRangesMigratedType);
        if( allRangesMigratedType == ExceptionTypes.ALL_RANGES_MIGRATED_IN){
            finishReconfiguration(partitionId);
        }
    }

    public boolean scheduleAsyncNonChunkPush() {
        return async_nonchunk_push;
    }

    public boolean scheduleAsyncNonChunkPull() {
        return async_nonchunk_pull;
    }

    public boolean scheduleAsyncPull() {
        return async_pull_immediately_in_work_queue;
    }
    
    public boolean queueAsyncPull() {
        return async_queue_pulls;
    }
    
    
    public void reportProfiler(String str, ProfileMeasurement pm, int partitionId, boolean writeToEventLog){
        if (pm.getInvocations()==0){
            return;
        }
        String logMsg = String.format("%s, MS=%s, Count=%s, PartitionId=%s",
                str,
                pm.getAverageThinkTimeMS(),
                pm.getInvocations(), partitionId);
        LOG.info(logMsg);
        if (writeToEventLog) FileUtil.appendEventToFile(logMsg);
    }
    
    public void reportProfiler(String str, FastIntHistogram h, int partitionId, boolean writeToEventLog){
        String histString = "Histogram=NoEntries";
        if (!h.isEmpty())
            histString = String.format("Histogram=\n%s", h.toString());
        else
            return;
        String logMsg = String.format("%s, PartitionId=%s, Histogram=\n%s",
                str,
                partitionId,
                histString);
        LOG.info(logMsg);
        if (writeToEventLog) FileUtil.appendEventToFile(logMsg);
    }
    
    public void reportProfiler(String desc, String valDesc, Number n, int partitionId, boolean writeToEventLog){
        if (n.intValue() == 0)
            return;
        String logMsg = String.format("%s, %s=%s, PartitionId=%s",
                desc,
                valDesc,
                n,
                partitionId);
        LOG.info(logMsg);
        if (writeToEventLog) FileUtil.appendEventToFile(logMsg);
    }
    
    public void showReconfigurationProfiler(boolean writeToEventLog) {
        if(hstore_conf.site.reconfig_profiling) {
            for (int p_id : hstore_site.getLocalPartitionIds().values()) {
                LOG.info("Showing reconfig stats");
                LOG.info(this.profilers[p_id].toString());
                
                reportProfiler("REPORT_AVG_DEMAND_PULL_TIME",this.profilers[p_id].on_demand_pull_time, p_id, writeToEventLog);
                
                reportProfiler("REPORT_AVG_ASYNC_PULL_TIME",this.profilers[p_id].async_pull_time, p_id, writeToEventLog);
                reportProfiler("REPORT_AVG_ASYNC_DEST_QUEUE_TIME",this.profilers[p_id].async_dest_queue_time, p_id, writeToEventLog);
                reportProfiler("REPORT_AVG_LIVE_PULL_RESPONSE_QUEUE_TIME",this.profilers[p_id].on_demand_pull_response_queue, p_id, writeToEventLog);
                reportProfiler("REPORT_PE_CHECK_TXN_TIME",this.profilers[p_id].pe_check_txn_time, p_id, writeToEventLog);
                reportProfiler("REPORT_PE_LIVE_PULL_BLOCK_TIME",this.profilers[p_id].pe_live_pull_block_time, p_id, writeToEventLog);
                

                reportProfiler("REPORT_EMPTY_LOADS", "Count", this.profilers[p_id].empty_loads, p_id, writeToEventLog);

                reportProfiler("REPORT_BLOCKED_QUEUE_SIZE",  this.profilers[p_id].pe_block_queue_size, p_id, writeToEventLog);
                reportProfiler("REPORT_BLOCKED_QUEUE_SIZE_GROWTH",  this.profilers[p_id].pe_block_queue_size_growth, p_id, writeToEventLog);
                reportProfiler("REPORT_EXTRACT_QUEUE_SIZE_GROWTH",  this.profilers[p_id].pe_extract_queue_size_growth, p_id, writeToEventLog);
                reportProfiler("REPORT_EXTRACT_PROC_TIME",  this.profilers[p_id].src_extract_proc_time, p_id, writeToEventLog);
                if(livePullKBMap != null && livePullKBMap.containsKey(p_id))
                    reportProfiler("REPORT_TOTAL_PULL_SIZE", "KB", livePullKBMap.get(p_id), p_id, writeToEventLog);

                if (detailed_timing) {
                    reportProfiler("REPORT_AVG_SRC_DATA_PULL_INIT",this.profilers[p_id].src_data_pull_req_init_time, p_id, writeToEventLog);
                    reportProfiler("REPORT_AVG_SRC_DATA_PULL_PROC",this.profilers[p_id].src_data_pull_req_proc_time, p_id, writeToEventLog);                    
                }
                String liveLog = this.executorMap.get(p_id).liveLogData.toString();
                LOG.info(liveLog);
                if (writeToEventLog) FileUtil.appendEventToFile(liveLog);
                
                
                
            }
        }
    }

    public void notifyPullResponse(int livePullIdentifier, int partitionId) {
        if (detailed_timing){
            try{
                this.profilers[partitionId].src_data_pull_req_proc_time.appendTime(dataPullResponseTimes.get(livePullIdentifier),System.currentTimeMillis());
            } catch(Exception ex) {
                LOG.info("Exception getting profiler timing", ex);
            }
        }
    }

    public void unblockingPullRequestSemaphore(int pullID, int partitionId, boolean isAsyncRequest) {

    	if(blockedRequests != null && blockedRequests.containsKey(pullID)){
    	    LOG.info("Callback of the semaphore has been received. Unblocking the semaphore we are blocked on" +
    	                "for partitionId : " + partitionId);
    	    blockedRequests.get(pullID).release();
    	}
    }

    public boolean isLive_pull() {
        return live_pull;
    }
    
    public boolean areAbortsEnabledForStopCopy(){
    	return abortsEnabledForStopCopy;
    }

    /**
     * Return the current partition for the data item if either are local.
     * If not return the expected
     * @param previousPartition
     * @param expectedPartition
     * @param catalogItem
     * @param value
     * @return
     */
    public int getPartitionId(int previousPartition, int expectedPartition, CatalogType catalogItem, Object value) {
        //TODO add a fast lookup with no exception
        if (executorMap.containsKey(expectedPartition)){
            //check with destination if we have it
            try{
                if (executorMap.get(expectedPartition).getReconfiguration_tracker().quickCheckKeyOwned(catalogItem, value))
                    return expectedPartition;
                else
                    return previousPartition;
            } catch(Exception e) {
                return previousPartition;
            }
        } else if (executorMap.containsKey(previousPartition)) {
            try{
                if (executorMap.get(previousPartition).getReconfiguration_tracker().quickCheckKeyOwned(catalogItem, value))
                    return previousPartition;
                else
                    return expectedPartition;
            } catch(Exception e) {
                return expectedPartition;
            }
        } else {
            return expectedPartition;
        }    
    }

    public static final String[] NEW_ORDER_FILTER = {"order_line","history","new_order"};
    public static final List<String> NEW_ORDER_FILTER_LIST = Arrays.asList(NEW_ORDER_FILTER);
    public static final String[] PAYMENT_FILTER = {"order_line","orders","stock","history"};
    public static final List<String> PAYMENT_FILTER_LIST = Arrays.asList(PAYMENT_FILTER);
    public static final String[] ORDER_STATUS_FILTER = {"warehouse","history","district","stock"};
    public static final List<String> ORDER_STATUS_FILTER_LIST = Arrays.asList(ORDER_STATUS_FILTER);
    public static final String[] STOCK_LEVEL_FILTER = {"warehouse","customer","history","new_order"};
    public static final List<String> STOCK_LEVEL_FILTER_LIST = Arrays.asList(STOCK_LEVEL_FILTER);
    public static final String[] DELIVERY_FILTER = {"warehouse","stock","district"};
    public static final List<String> DELIVERY_FILTER_LIST = Arrays.asList(DELIVERY_FILTER);
    
    //Force reconfig look ups to go to destination
    public static final boolean FORCE_DESTINATION = false;
    




}
