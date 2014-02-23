/**
 * 
 */
package edu.brown.hstore;

import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;
import org.junit.Before;
import org.junit.Test;
import org.voltdb.VoltTable;
import org.voltdb.VoltType;
import org.voltdb.catalog.Site;
import org.voltdb.catalog.Table;
import org.voltdb.client.Client;
import org.voltdb.jni.ExecutionEngine;
import org.voltdb.utils.Pair;
import org.voltdb.utils.VoltTableUtil;

import edu.brown.BaseTestCase;
import edu.brown.benchmark.ycsb.YCSBConstants;
import edu.brown.catalog.CatalogUtil;
import edu.brown.hashing.PlannedHasher;
import edu.brown.hashing.TwoTieredRangeHasher;
import edu.brown.hashing.PlannedPartitions.PartitionRange;
import edu.brown.hashing.PlannedPartitions.PartitionedTable;
import edu.brown.hashing.ReconfigurationPlan.ReconfigurationRange;
import edu.brown.hashing.ReconfigurationPlan.ReconfigurationTable;
import edu.brown.hstore.conf.HStoreConf;
import edu.brown.hstore.reconfiguration.ReconfigurationUtil;
import edu.brown.utils.CollectionUtil;
import edu.brown.utils.ProjectType;

/**
 * @author aelmore
 */
public class TestReconfigurationEE extends BaseTestCase {

    private static final Logger LOG = Logger.getLogger(TestReconfigurationEE.class);
    private static final int NUM_PARTITIONS = 1;
    private static final long NUM_TUPLES = 1000;
    private static final String TARGET_TABLE = YCSBConstants.TABLE_NAME;

    private HStoreSite hstore_site;
    private HStoreConf hstore_conf;
    private Client client;

    private PartitionExecutor executor;
    private ExecutionEngine ee;
    private Table catalog_tbl;

    // private int ycsbTableId(Catalog catalog) {
    // return
    // catalog.getClusters().get("cluster").getDatabases().get("database").getTables().get(TARGET_TABLE).getRelativeIndex();
    // }
    @Before
    public void setUp() throws Exception {
        super.setUp(ProjectType.YCSB);
        initializeCatalog(1, 1, NUM_PARTITIONS);

        // Just make sure that the Table has the evictable flag set to true
        this.catalog_tbl = getTable(TARGET_TABLE);

        Site catalog_site = CollectionUtil.first(CatalogUtil.getCluster(catalog).getSites());
        hstore_conf = HStoreConf.singleton();

        hstore_conf.site.coordinator_sync_time = false;
        hstore_conf.global.reconfiguration_enable = true;
        hstore_conf.global.hasher_class = "edu.brown.hashing.PlannedHasher";
        hstore_conf.global.hasher_plan = PlannedHasher.YCSB_TEST;

        hstore_conf.site.status_enable = false;

        this.hstore_site = createHStoreSite(catalog_site, hstore_conf);
        this.executor = hstore_site.getPartitionExecutor(0);
        assertNotNull(this.executor);
        this.ee = executor.getExecutionEngine();
        assertNotNull(this.executor);

        this.client = createClient();
    }

    @Override
    protected void tearDown() throws Exception {
        if (this.client != null)
            this.client.close();
        if (this.hstore_site != null)
            this.hstore_site.shutdown();
    }

    private void loadData(Long numTuples) throws Exception {
        // Load in a bunch of dummy data for this table
        VoltTable vt = CatalogUtil.getVoltTable(catalog_tbl);
        assertNotNull(vt);
        for (int i = 0; i < numTuples; i++) {
            Object row[] = VoltTableUtil.getRandomRow(catalog_tbl);
            row[0] = i;
            vt.addRow(row);
        } // FOR
        this.executor.loadTable(1000L, catalog_tbl, vt, false);

    }

    @Test
    public void testExtractDataLarge() throws Exception {
        List<PartitionRange<Long>> olds = new ArrayList<>();
        List<PartitionRange<Long>> news = new ArrayList<>();
        long tuples = 15000;
        this.loadData(tuples);
        assertTrue(true);
        String rangeString = String.format("0-%s", tuples);
        olds.add(new PartitionRange<Long>(VoltType.BIGINT, 1, rangeString));
        PartitionedTable<Long> old_table = new PartitionedTable<Long>(olds, TARGET_TABLE, VoltType.BIGINT, this.catalog_tbl);

        news.add(new PartitionRange<Long>(VoltType.BIGINT, 2, rangeString));
        PartitionedTable<Long> new_table = new PartitionedTable<Long>(news, TARGET_TABLE, VoltType.BIGINT, this.catalog_tbl);

        ReconfigurationTable<Long> reconfig = new ReconfigurationTable<>(old_table, new_table);
        long rowCount = 0;
        for (ReconfigurationRange<Long> range : reconfig.getReconfigurations()) {
            assertNotNull(range);
            // assertEquals((Long)tuples, range.getMax_exclusive());

            // ReconfigurationRange<Long> range = new
            // ReconfigurationRange<Long>("usertable", VoltType.BIGINT, new
            // Long(0), tuples, 1, 2);
            VoltTable extractTable = ReconfigurationUtil.getExtractVoltTable(range);
            boolean moreData = true;
            while (moreData) {
                Pair<VoltTable, Boolean> resTable = this.ee.extractTable(this.catalog_tbl, this.catalog_tbl.getRelativeIndex(), extractTable, 1, 1, 1, executor.getNextRequestToken(), 1);
                rowCount += resTable.getFirst().getRowCount();
                // assertEquals(false, resTable.getSecond().booleanValue());
                moreData = resTable.getSecond().booleanValue();
            }
        }
        assertEquals(tuples, rowCount);
    }

    @Test
    public void testExtractSize() throws Exception {
        this.loadData(150l);
        assertTrue(true);
        ReconfigurationRange<Long> range = new ReconfigurationRange<Long>("usertable", VoltType.BIGINT, new Long(1), new Long(100), 1, 2);
        VoltTable extractTable = ReconfigurationUtil.getExtractVoltTable(range);
        int deleteToken = 47;
        Pair<VoltTable, Boolean> resTable = this.ee.extractTable(this.catalog_tbl, this.catalog_tbl.getRelativeIndex(), extractTable, 1, 1, 1, deleteToken, 1, 10 * 1024);
        assertEquals(10, resTable.getFirst().getRowCount());
        LOG.info("confirming extract request");
    }

    @Test
    public void testMultiExtract() throws Exception {
        this.loadData(150l);
        assertTrue(true);
        List<ReconfigurationRange> ranges = new ArrayList<>();

        ranges.add(new ReconfigurationRange<Long>("usertable", VoltType.BIGINT, new Long(1), new Long(10), 1, 2));
        ranges.add(new ReconfigurationRange<Long>("usertable", VoltType.BIGINT, new Long(100), new Long(104), 1, 2));

        VoltTable extractTable = ReconfigurationUtil.getExtractVoltTable(ranges);
        int deleteToken = 47;
        Pair<VoltTable, Boolean> resTable = this.ee.extractTable(this.catalog_tbl, this.catalog_tbl.getRelativeIndex(), extractTable, 1, 1, 1, deleteToken, 1, 20 * 1024);
        assertEquals(13, resTable.getFirst().getRowCount());
        LOG.info("confirming extract request");
    }

    @Test
    public void testMultiExtractWithLimit() throws Exception {
        this.loadData(150l);
        assertTrue(true);
        List<ReconfigurationRange> ranges = new ArrayList<>();

        ranges.add(new ReconfigurationRange<Long>("usertable", VoltType.BIGINT, new Long(1), new Long(50), 1, 2));
        ranges.add(new ReconfigurationRange<Long>("usertable", VoltType.BIGINT, new Long(100), new Long(104), 1, 2));

        VoltTable extractTable = ReconfigurationUtil.getExtractVoltTable(ranges);
        int deleteToken = 47;
        Pair<VoltTable, Boolean> resTable = this.ee.extractTable(this.catalog_tbl, this.catalog_tbl.getRelativeIndex(), extractTable, 1, 1, 1, deleteToken, 1, 10 * 1024);
	int totalRows = resTable.getFirst().getRowCount();
        assertEquals(10, totalRows);
        assertTrue(resTable.getSecond());

	while(resTable.getSecond()) {
	    resTable = this.ee.extractTable(this.catalog_tbl, this.catalog_tbl.getRelativeIndex(), extractTable, 1, 1, 1, deleteToken, 1, 10 * 1024);
	    totalRows += resTable.getFirst().getRowCount();
	}

        assertEquals(53, totalRows);

    }

    @Test
    public void testExtractAndConfirmData() throws Exception {
        this.loadData(NUM_TUPLES);
        assertTrue(true);
        ReconfigurationRange<Long> range = new ReconfigurationRange<Long>("usertable", VoltType.BIGINT, new Long(100), new Long(102), 1, 2);
        VoltTable extractTable = ReconfigurationUtil.getExtractVoltTable(range);
        int deleteToken = 47;
        Pair<VoltTable, Boolean> resTable = this.ee.extractTable(this.catalog_tbl, this.catalog_tbl.getRelativeIndex(), extractTable, 1, 1, 1, deleteToken, 1);
        assertTrue(resTable.getFirst().getRowCount() == 2);
        LOG.info("confirming extract request");
        boolean success = this.ee.updateExtractRequest(deleteToken, true);
        assertTrue(success);
        // LOG.info("Redundant confirm delete");
        // success = this.ee.updateExtractRequest(deleteToken, true);
        // assertTrue(success==false);

        range = new ReconfigurationRange<Long>("usertable", VoltType.BIGINT, new Long(110), new Long(120), 1, 2);
        extractTable = ReconfigurationUtil.getExtractVoltTable(range);

        resTable = this.ee.extractTable(this.catalog_tbl, this.catalog_tbl.getRelativeIndex(), extractTable, 1, 1, 1, ++deleteToken, 1);
        assertTrue(resTable.getFirst().getRowCount() == 10);

        // TODO undo migration
        // LOG.info("undo  extract request" );
        // success = this.ee.updateExtractRequest(deleteToken, false);
    }

    @Test
    public void testExtractData() throws Exception {

        this.loadData(NUM_TUPLES);
        assertTrue(true);
        ReconfigurationRange<Long> range = new ReconfigurationRange<Long>("usertable", VoltType.BIGINT, new Long(100), new Long(102), 1, 2);
        VoltTable extractTable = ReconfigurationUtil.getExtractVoltTable(range);
        Pair<VoltTable, Boolean> resTable = this.ee.extractTable(this.catalog_tbl, this.catalog_tbl.getRelativeIndex(), extractTable, 1, 1, 1, executor.getNextRequestToken(), 1);
        assertTrue(resTable.getFirst().getRowCount() == 2);
        LOG.info("Results : " + resTable.getFirst().toString(true));
        resTable = this.ee.extractTable(this.catalog_tbl, this.catalog_tbl.getRelativeIndex(), extractTable, 1, 1, 1, executor.getNextRequestToken(), 1);
        assertTrue(resTable.getFirst().getRowCount() == 0);

        range = new ReconfigurationRange<Long>("usertable", VoltType.BIGINT, new Long(50), new Long(60), 1, 2);
        extractTable = ReconfigurationUtil.getExtractVoltTable(range);

        resTable = this.ee.extractTable(this.catalog_tbl, this.catalog_tbl.getRelativeIndex(), extractTable, 1, 1, 1, executor.getNextRequestToken(), 1);
        assertTrue(resTable.getFirst().getRowCount() == 10);
        LOG.info("Results : " + resTable.getFirst().toString(true));

        range = new ReconfigurationRange<Long>("usertable", VoltType.BIGINT, new Long(55), new Long(55), 1, 2);
        extractTable = ReconfigurationUtil.getExtractVoltTable(range);
        resTable = this.ee.extractTable(this.catalog_tbl, this.catalog_tbl.getRelativeIndex(), extractTable, 1, 1, 1, executor.getNextRequestToken(), 1);
        assertTrue(resTable.getFirst().getRowCount() == 0);

        range = new ReconfigurationRange<Long>("usertable", VoltType.BIGINT, new Long(998), new Long(1002), 1, 2);
        extractTable = ReconfigurationUtil.getExtractVoltTable(range);
        resTable = this.ee.extractTable(this.catalog_tbl, this.catalog_tbl.getRelativeIndex(), extractTable, 1, 1, 1, executor.getNextRequestToken(), 1);
        assertTrue(resTable.getFirst().getRowCount() == 2);

        range = new ReconfigurationRange<Long>("usertable", VoltType.BIGINT, new Long(995), new Long(998), 1, 2);
        extractTable = ReconfigurationUtil.getExtractVoltTable(range);
        resTable = this.ee.extractTable(this.catalog_tbl, this.catalog_tbl.getRelativeIndex(), extractTable, 1, 1, 1, executor.getNextRequestToken(), 1);
        assertTrue(resTable.getFirst().getRowCount() == 3);

        range = new ReconfigurationRange<Long>("usertable", VoltType.BIGINT, new Long(200), new Long(300), 1, 2);
        extractTable = ReconfigurationUtil.getExtractVoltTable(range);
        resTable = this.ee.extractTable(this.catalog_tbl, this.catalog_tbl.getRelativeIndex(), extractTable, 1, 1, 1, executor.getNextRequestToken(), 1);
        assertTrue(resTable.getFirst().getRowCount() == 100);

        range = new ReconfigurationRange<Long>("usertable", VoltType.BIGINT, new Long(300), new Long(300), 1, 2);
        extractTable = ReconfigurationUtil.getExtractVoltTable(range);
        resTable = this.ee.extractTable(this.catalog_tbl, this.catalog_tbl.getRelativeIndex(), extractTable, 1, 1, 1, executor.getNextRequestToken(), 1);
        assertTrue(resTable.getFirst().getRowCount() == 1);

        range = new ReconfigurationRange<Long>("usertable", VoltType.BIGINT, new Long(301), new Long(302), 1, 2);
        extractTable = ReconfigurationUtil.getExtractVoltTable(range);
        resTable = this.ee.extractTable(this.catalog_tbl, this.catalog_tbl.getRelativeIndex(), extractTable, 1, 1, 1, executor.getNextRequestToken(), 1);
        assertTrue(resTable.getFirst().getRowCount() == 1);

        // TODO ae andy we don't want to crash the system. need to find right
        // exception
        /*
         * boolean excCaught = false; try{ range = new
         * ReconfigurationRange<Long>("usertable", VoltType.BIGINT, new
         * Long(301), new Long(300), 1, 2); extractTable =
         * ReconfigurationUtil.getExtractVoltTable(range); resTable=
         * this.ee.extractTable(this.catalog_tbl.getRelativeIndex(),
         * extractTable, 1, 1, 1); } catch(Exception ex){ excCaught=true; }
         * assertTrue(excCaught);
         */

    }

}
