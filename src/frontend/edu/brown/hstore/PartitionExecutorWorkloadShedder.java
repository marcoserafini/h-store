package edu.brown.hstore;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.log4j.Logger;

import edu.brown.hstore.Hstoreservice.Status;
import edu.brown.hstore.conf.HStoreConf;
import edu.brown.hstore.txns.AbstractTransaction;
import edu.brown.interfaces.Shutdownable;
import edu.brown.logging.LoggerUtil;
import edu.brown.logging.LoggerUtil.LoggerBoolean;
import edu.brown.utils.ExceptionHandlingRunnable;
import edu.brown.utils.MathUtil;
import edu.brown.utils.PartitionSet;
import edu.brown.utils.StringUtil;

/**
 * Special thread that can check whether the partition's queues are imbalanced 
 * (because of speed imbalance of the cores), and then shed transactions to
 * free up slots in the ClientInterface 
 * @author pavlo
 */
public class PartitionExecutorWorkloadShedder extends ExceptionHandlingRunnable implements Shutdownable {
    private static final Logger LOG = Logger.getLogger(PartitionExecutorWorkloadShedder.class);
    private static final LoggerBoolean debug = new LoggerBoolean(LOG.isDebugEnabled());
    private static final LoggerBoolean trace = new LoggerBoolean(LOG.isTraceEnabled());
    static {
        LoggerUtil.attachObserver(LOG, debug, trace);
    }
    
    private final HStoreSite hstore_site;
    private final HStoreConf hstore_conf;
    private final PartitionSet partitions;
    private final TransactionQueueManager queueManager;
    private boolean shutdown = false;
    private boolean initialized = false;
    
    // ----------------------------------------------------------------------------
    // PER PARTITION DATA MEMBERS
    // ----------------------------------------------------------------------------
    
    private final int lastQueueSize[];
    private final long totalRemoved[];
    private final PartitionExecutor executors[];
    private final TransactionInitPriorityQueue queues[];
    
    /**
     * Constructor
     * @param hstore_site
     */
    public PartitionExecutorWorkloadShedder(HStoreSite hstore_site) {
        this.hstore_site = hstore_site;
        this.hstore_conf = hstore_site.getHStoreConf();
        this.partitions = hstore_site.getLocalPartitionIds();
        this.queueManager = hstore_site.getTransactionQueueManager();
        
        int numPartitions = hstore_site.getCatalogContext().numberOfPartitions;
        this.lastQueueSize = new int[numPartitions];
        this.totalRemoved = new long[numPartitions];
        this.executors = new PartitionExecutor[numPartitions];
        this.queues = new TransactionInitPriorityQueue[numPartitions];
        
        Arrays.fill(this.lastQueueSize, 0);
        Arrays.fill(this.totalRemoved, 0);
    }
    
    protected void init() {
        if (debug.val)
            LOG.debug("Initializing " + this.getClass().getSimpleName());
        
        this.hstore_site.getThreadManager().registerProcessingThread();
        for (int partition : this.partitions.values()) {
            this.executors[partition] = hstore_site.getPartitionExecutor(partition);
            this.queues[partition] = this.queueManager.getInitQueue(partition);
        } // FOR
    }

    @Override
    public void runImpl() {
        if (this.initialized == false) {
            synchronized (this) {
                if (this.initialized == false) {
                    this.init();
                    this.initialized = true;
                }
            } // SYNCH
        }
         
        if (debug.val)
            LOG.debug("Checking to see whether partition queues are skewed");
        
        // Go through and get the queue size for each PartitionExecutor
        int total = 0;
        int sizes[] = new int[this.partitions.size()];
        int offset = 0;
        for (int partition : this.partitions.values()) {
            this.lastQueueSize[partition] = sizes[offset++] = this.queues[partition].size();
            total += this.lastQueueSize[partition];
        } // FOR
        
        // If a partition is above the threshold, then we want to shed work from its queue
        if (total > 0) {
            double avg = MathUtil.arithmeticMean(sizes);
            double stdev = MathUtil.stdev(sizes);
            int threshold = (int)(avg + (stdev * hstore_conf.site.queue_shedder_stdev_multiplier));
            
            // *********************************** DEBUG ***********************************
            if (debug.val) {
                @SuppressWarnings("unchecked")
                Map<String, Object> maps[] = new Map[2];
                int idx = 0;
                
                maps[idx] = new LinkedHashMap<String, Object>();
                maps[idx].put("Total", total);
                maps[idx].put("Average", avg);
                maps[idx].put("Stdev", stdev);
                maps[idx].put("Threshold", threshold);
                
                maps[++idx] = new LinkedHashMap<String, Object>();
                for (int partition : this.partitions.values()) {
                    maps[idx].put("Partition " + partition, this.lastQueueSize[partition]);
                } // FOR
                
                LOG.debug("Queue Stats:\n" + StringUtil.formatMaps(maps));
            }
            // *********************************** DEBUG ***********************************
            
            for (int partition : this.partitions.values()) {
                if (threshold < this.lastQueueSize[partition]) {
                    this.shedWork(partition, this.lastQueueSize[partition] - threshold);
                }
            } // FOR
        }
    }
    
    /**
     * Grab the last items from the partition's workload queue and reject the txns
     * @param partition
     * @param to_remove
     */
    protected void shedWork(int partition, int to_remove) {
        if (debug.val)
            LOG.debug(String.format("Attempting to shed %d out of %d txns from from partition %d",
                      to_remove, this.lastQueueSize[partition], partition));
        
        // Grab txns from the back of the queue and reject them
        int offset = this.lastQueueSize[partition] - to_remove;
        int idx = 0;
        int removed = 0;
        for (AbstractTransaction ts : this.queues[partition]) {
            if (idx++ < offset) continue;
            
            // Skip this is if it's not initialized. Yes, I know that this
            // is a race condition but it's good enough for now
            if (ts.isInitialized() == false) continue;
            
            if (trace.val)
                LOG.trace(String.format("Rejecting " + ts + " at partition " + partition));
            try {
                this.queueManager.lockQueueFinished(ts, Status.ABORT_REJECT, partition);
                removed++;
            } catch (Throwable ex) {
                String msg = String.format("Unexpected error when trying to reject %s at partition %d",
                                           ts, partition);
                LOG.error(msg, ex);
            }
        } // FOR
        this.totalRemoved[partition] += removed;
        
        if (debug.val)
            LOG.debug(String.format("Removed %d txns from partition %d [origSize=%d, newSize=%d, totalRemoved=%d]",
                      removed, partition,
                      this.lastQueueSize[partition], this.queues[partition].size(), this.totalRemoved[partition]));
    }

    
    @Override
    public boolean isShuttingDown() {
        return (this.shutdown == true);
    }
    
    @Override
    public void shutdown() {
        this.shutdown = true;
    }

    @Override
    public void prepareShutdown(boolean error) {
        // Nothing to do...
    }

}
