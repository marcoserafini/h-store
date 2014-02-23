/**
 * 
 */
package edu.brown.hashing;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.log4j.Logger;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONStringer;
import org.voltdb.CatalogContext;
import org.voltdb.VoltType;
import org.voltdb.catalog.CatalogType;
import org.voltdb.catalog.Column;
import org.voltdb.catalog.Database;
import org.voltdb.catalog.Procedure;
import org.voltdb.catalog.Statement;
import org.voltdb.catalog.Table;
import org.voltdb.utils.VoltTypeUtil;

import edu.brown.catalog.DependencyUtil;
import edu.brown.hstore.HStoreConstants;
import edu.brown.logging.LoggerUtil;
import edu.brown.logging.LoggerUtil.LoggerBoolean;
import edu.brown.mappings.ParameterMappingsSet;
import edu.brown.utils.FileUtil;
import edu.brown.utils.JSONSerializable;
import edu.brown.utils.StringUtil;

//       TODO This class likely needs to be relocated (ae)
/**
 * @author aelmore A container for statically defined partitions plans. Each
 *         plan will contain multiple partition phases. Each partition phase
 *         will contain a list of tables that dictate how the table is
 *         partitioned. <br>
 *         PlannedPartitions Hierarchy:
 *         <ul>
 *         <li>Map[String, PartitionPhase] partition_phase_map
 *         <ul>
 *         <li>Map[String, PartitionedTable] tables_map
 *         <ul>
 *         <li>List[PartitionRange] partitions
 *         <ul>
 *         <li>PartitionRange: min,max,partition_id
 *         </ul>
 *         </ul>
 *         </ul>
 *         </ul>
 */

public class PlannedPartitions implements JSONSerializable {
    private static final Logger LOG = Logger.getLogger(PlannedPartitions.class);
    private static final LoggerBoolean debug = new LoggerBoolean(LOG.isDebugEnabled());
    private static final LoggerBoolean trace = new LoggerBoolean(LOG.isTraceEnabled());
    public static final String PLANNED_PARTITIONS = "partition_plans";
    public static final String TABLES = "tables";
    public static final String PARTITIONS = "partitions";
    private static final String DEFAULT_TABLE = "default_table";
    public static final VoltType DEFAULT_VOLTTYPE = VoltType.BIGINT;

    static {
        LoggerUtil.attachObserver(LOG, debug, trace);
    }

    private CatalogContext catalog_context;
    private Map<String, VoltType> table_vt_map;
    private Map<String, PartitionPhase> partition_phase_map;
    private Map<CatalogType, String> catalog_to_table_map;
    private Map<String, List<String>> relatedTablesMap;
    private ParameterMappingsSet paramMappings;
    private String current_phase;
    private String previous_phase;
    private String default_table = null;

    public PlannedPartitions(CatalogContext catalog_context, File planned_partition_json_file) throws Exception {
        this(catalog_context, new JSONObject(FileUtil.readFile(planned_partition_json_file)));
    }

    public PlannedPartitions(CatalogContext catalog_context, JSONObject planned_partition_json) throws Exception {
        this.current_phase = null;
        this.previous_phase = null;
        this.catalog_context = catalog_context;
        this.partition_phase_map = new HashMap<>();
        this.catalog_to_table_map = new HashMap<>();
        this.paramMappings = catalog_context.paramMappings;
        this.relatedTablesMap = new HashMap<>();

        Set<String> partitionedTables = getExplicitPartitionedTables(planned_partition_json);
        // TODO find catalogContext.getParameter mapping to find
        // statement_column
        // from project mapping (ae)
        assert planned_partition_json.has(DEFAULT_TABLE) : "default_table missing from planned partition json";
        this.default_table = planned_partition_json.getString(DEFAULT_TABLE);
        this.table_vt_map = new HashMap<>();
        for (Table table : catalog_context.getDataTables()) {
            String tableName = table.getName().toLowerCase();
            Column partitionCol = table.getPartitioncolumn();
            if (partitionCol == null) {
                LOG.info(String.format("Partition col for table %s is null. Skipping", tableName));
            } else {
                LOG.info(String.format("Adding table:%s partitionCol:%s %s", tableName, partitionCol, VoltType.get(partitionCol.getType())));
                this.table_vt_map.put(tableName, VoltType.get(partitionCol.getType()));
                this.catalog_to_table_map.put(partitionCol, tableName);
            }
        }

        for (Procedure proc : catalog_context.procedures) {
            if (!proc.getSystemproc()) {
                String table_name = this.catalog_to_table_map.get(proc.getPartitioncolumn());
                if ((table_name == null) || (table_name.equals("null")) || (table_name.trim().length() == 0)) {
                    LOG.info(String.format("Using default table %s for procedure: %s ", this.default_table, proc.toString()));
                    table_name = this.default_table;
                } else {
                    LOG.info(table_name + " adding procedure: " + proc.toString());
                }
                this.catalog_to_table_map.put(proc, table_name);
                for (Statement statement : proc.getStatements()) {
                    LOG.debug(table_name + " adding statement: " + statement.toString());

                    this.catalog_to_table_map.put(statement, table_name);
                }

            }
        }
        // We need to track which tables are partitioned on another table in
        // order to generate the reconfiguration ranges for those tables,
        // because they are not explicitly partitioned in the plan.
        DependencyUtil dependUtil = DependencyUtil.singleton(catalog_context.database);
        Map<String, String> partitionedTablesByFK = new HashMap<>();

        for (Table table : catalog_context.getDataTables()) {
            String tableName = table.getName().toLowerCase();
            // Making the assumption that the same tables are in all phases TODO
            // verify this
            if (partitionedTables.contains(tableName) == false) {

                Column partitionCol = table.getPartitioncolumn();
                if (partitionCol == null) {
                    LOG.info(tableName + " is not partitioned and has no partition column. skipping");
                    continue;
                } else {
                    LOG.info(tableName + " is not explicitly partitioned.");
                }
                List<Column> depCols = dependUtil.getAncestors(partitionCol);
                boolean partitionedParentFound = false;
                List<String> relatedTables = new ArrayList<>();
                for (Column c : depCols) {
                    CatalogType p = c.getParent();
                    if (p instanceof Table) {
                        // if in table then map to it
                        String relatedTblName = p.getName().toLowerCase();
                        LOG.info(String.format("Table %s is related to %s",tableName,relatedTblName));
                        relatedTables.add(relatedTblName);
                        if (partitionedTables.contains(relatedTblName)) {
                            LOG.info("parent partitioned table : " + p + " : " + relatedTblName);
                            partitionedTablesByFK.put(tableName, relatedTblName);
                            partitionedParentFound = true;
                            if (catalog_to_table_map.containsKey(table))
                                LOG.error("ctm has table already : " + table);
                            catalog_to_table_map.put(table, relatedTblName);
                            if (catalog_to_table_map.containsKey(partitionCol)) {
                                LOG.error("ctm has part col : " + partitionCol + " : " + catalog_to_table_map.get(partitionCol));
                            }
                            //TODO catalog_to_table_map.put(partitionCol, relatedTblName);
                            LOG.info("no relationships on look up " +partitionCol + " : " + tableName);
                            catalog_to_table_map.put(partitionCol, tableName);
                        }
                    }
                }
                if(!relatedTables.isEmpty()){
                    LOG.info("Associating the list of related tables for :"+ tableName);
                    relatedTables.add(tableName);
                    relatedTablesMap.put(tableName, relatedTables);
                }
                if (!partitionedParentFound) {
                    throw new RuntimeException("No partitioned relationship found for table : " + tableName + " partitioned:" + partitionedTables.toString());
                }
            }
        }

        if (planned_partition_json.has(PLANNED_PARTITIONS)) {
            JSONObject phases = planned_partition_json.getJSONObject(PLANNED_PARTITIONS);
            String first_key = null;
            Iterator<String> keys = phases.keys();
            while (keys.hasNext()) {
                String key = keys.next();

                JSONObject phase = phases.getJSONObject(key);
                this.partition_phase_map.put(key, new PartitionPhase(catalog_context, this.table_vt_map, phase, partitionedTablesByFK));

                // Use the first phase by default
                if (first_key == null) {
                    first_key = key;
                    this.setPartitionPhase(first_key);
                }
            }

        } else {
            throw new JSONException(String.format("JSON file is missing key \"%s\". ", PLANNED_PARTITIONS));
        }

        // TODO check to make sure partitions exist that are in the plan (ae)

    }

    /**
     * Get the explicit partitioned tables and ensure that each phase has the
     * same set of tables
     * 
     * @param planned_partition_json
     * @return the set of tables in the partition plan
     */
    public static Set<String> getExplicitPartitionedTables(JSONObject planned_partition_json) {
        try {
            Set<String> tables = null;
            if (planned_partition_json.has(PLANNED_PARTITIONS)) {
                JSONObject phases = planned_partition_json.getJSONObject(PLANNED_PARTITIONS);
                Iterator<String> keys = phases.keys();
                while (keys.hasNext()) {
                    JSONObject phase = phases.getJSONObject(keys.next());
                    Set<String> phase_tables = new HashSet<>();
                    Iterator<String> table_names = phase.getJSONObject(TABLES).keys();
                    while (table_names.hasNext()) {
                        phase_tables.add(table_names.next());
                    }
                    if (tables == null) {
                        // First set of tables
                        tables = phase_tables;
                    } else {
                        // check if equal
                        if (tables.equals(phase_tables) == false) {
                            throw new RuntimeException(String.format("Partition plan has mistmatched tables (%s) != (%s)", tables, phase_tables));
                        }
                    }
                }
            }
            return tables;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Get the partition id for a given table and partition id/key
     * 
     * @param table_name
     * @param id
     * @return the partition id, or -1 / null partition if the id/key is not
     *         found in the plan
     * @throws Exception
     */
    public int getPartitionId(String table_name, Object id) throws Exception {
        PartitionPhase phase = this.partition_phase_map.get(this.getCurrent_phase());
        PartitionedTable<?> table = phase.getTable(table_name);
        if (table == null) {
            if (debug.val)
                LOG.debug(String.format("Table not found: %s, using default:%s ", table_name, this.default_table));
            table = phase.getTable(this.default_table);
            if (table == null) {
                throw new RuntimeException(String.format("Default partition table is null. Lookup table:%s Default Table:%s", table_name, this.default_table));
            }
        }
        assert table != null : "Table not found " + table_name;
        return table.findPartition(id);
    }

    public int getPartitionId(CatalogType catalog, Object id) throws Exception {
        String table_name = this.catalog_to_table_map.get(catalog);
        return this.getPartitionId(table_name, id);
    }

    public String getTableName(CatalogType catalog) {
        return this.catalog_to_table_map.get(catalog);
    }

    /**
     * Get the previous partition id for a given table and partition id/key
     * 
     * @param table_name
     * @param id
     * @return the partition id, or -1 / null partition if the id/key is not
     *         found in the plan OR if there is no previous phase
     * @throws Exception
     */
    public int getPreviousPartitionId(String table_name, Object id) throws Exception {
        String previousPhase = this.getPreviousPhase_phase();
        if (previousPhase == null)
            return -1;
        PartitionPhase phase = this.partition_phase_map.get(previousPhase);
        PartitionedTable<?> table = phase.getTable(table_name);
        if (table == null){
            table = phase.getTable(table_name.toLowerCase());
            if (table == null) {
                throw new Exception("Unable to find table "+ table_name + " in phase  " + previousPhase);
            }
        }
        assert table != null : "Table not found " + table_name;
        return table.findPartition(id);
    }

    public int getPreviousPartitionId(CatalogType catalog, Object id) throws Exception {
        String previousPhase = this.getPreviousPhase_phase();
        if (previousPhase == null)
            return -1;

        String table_name = this.catalog_to_table_map.get(catalog);
        PartitionPhase phase = this.partition_phase_map.get(previousPhase);
        PartitionedTable<?> table = phase.getTable(table_name);
        if (table == null){
            table = phase.getTable(table_name.toLowerCase());
            if (table == null) {
                throw new Exception("Unable to find table "+ table_name + " in phase  " + previousPhase);
            }
        }
        assert table != null : "Table not found " + table_name;
        return table.findPartition(id);
    }

    // ******** Containers *****************************************/

    /**
     * @author aelmore Holds the phases/epochs/version of a partition plan
     */
    public static class PartitionPhase {
        protected Map<String, PartitionedTable<? extends Comparable<?>>> tables_map;

        @SuppressWarnings("unchecked")
        public List<PartitionRange<? extends Comparable<?>>> getPartitions(String table_name) {
            return (List<PartitionRange<? extends Comparable<?>>>) this.tables_map.get(table_name);
        }

        public PartitionedTable<? extends Comparable<?>> getTable(String table_name) {
            return this.tables_map.get(table_name);
        }

        /**
         * Create a new partition phase
         * 
         * @param catalog_db
         * @param table_vt_map
         *            mapping of table names to volt type of partition col
         * @param phase
         *            JSONObject
         * @param partitionedTablesByFK
         */
        public PartitionPhase(CatalogContext catalog_context, Map<String, VoltType> table_vt_map, JSONObject phase, Map<String, String> partitionedTablesByFK) throws Exception {
            this.tables_map = new HashMap<String, PlannedPartitions.PartitionedTable<? extends Comparable<?>>>();
            assert (phase.has(TABLES));
            JSONObject json_tables = phase.getJSONObject(TABLES);
            Iterator<String> table_names = json_tables.keys();
            while (table_names.hasNext()) {
                String table_name = table_names.next();
                VoltType vt = null;
                if (table_vt_map.containsKey(table_name.toLowerCase())) {
                    vt = table_vt_map.get(table_name);
                } else {
                    LOG.info(String.format("Using default voltType %s for table '%s' ", DEFAULT_VOLTTYPE, table_name));
                    vt = DEFAULT_VOLTTYPE;
                }

                JSONObject table_json = json_tables.getJSONObject(table_name.toLowerCase());
                // Class<?> c = table_vt_map.get(table_name).classFromType();
                // TODO fix partitiontype
                this.tables_map.put(table_name, new PartitionedTable<Long>(vt, table_name, table_json, catalog_context.getTableByName(table_name)));
            }

            //Add entries for tables that are partitioned on other columns
            for (Entry<String, String> partitionedFK : partitionedTablesByFK.entrySet()) {
                String table_name = partitionedFK.getKey();
                String fk_table_name = partitionedFK.getValue();
                if (json_tables.has(fk_table_name) == false) {
                    throw new RuntimeException(String.format("For table %s, the foreignkey partitioned table %s is not explicitly partitioned ", table_name,fk_table_name));
                }
                LOG.info(String.format("Adding FK partitioning %s->%s", table_name, fk_table_name));
                this.tables_map.put(partitionedFK.getKey(), this.tables_map.get(partitionedFK.getValue()).clone(table_name, catalog_context.getTableByName(table_name)));
            }
        }

        protected PartitionPhase(Map<String, PlannedPartitions.PartitionedTable<? extends Comparable<?>>> table_map) {
            this.tables_map = table_map;
        }
    }

    /**
     * @author aelmore Holds the partitioning for a table, during a given phase
     * @param <T>
     *            The type of the ID which is partitioned on. Comparable
     */
    public static class PartitionedTable<T extends Comparable<T>> {

        protected List<PartitionRange<T>> partitions;
        protected String table_name;
        private VoltType vt;
        private Table catalog_table;
        private JSONObject table_json;

        public PartitionedTable(VoltType vt, String table_name, JSONObject table_json, Table catalog_table) throws Exception {
            this.catalog_table = catalog_table;
            this.partitions = new ArrayList<>();
            this.table_name = table_name;
            this.table_json = table_json;
            this.vt = vt;
            assert (table_json.has(PARTITIONS));
            JSONObject partitions_json = table_json.getJSONObject(PARTITIONS);
            Iterator<String> partitions = partitions_json.keys();
            while (partitions.hasNext()) {
                String partition = partitions.next();
                // TODO do we need more than ints, what about specifying ranges
                // as
                // replicated tables (ae)
                int partition_id = Integer.parseInt(partition);
                this.addPartitionRanges(partition_id, partitions_json.getString(partition));
            }
            Collections.sort(this.partitions);
        }
        
        public PartitionedTable<T> clone(String new_table_name, Table new_catalog_table) throws Exception{
            return new PartitionedTable<T>(this.vt,new_table_name, this.table_json,new_catalog_table);
        }

        public PartitionedTable(List<PartitionRange<T>> partitions, String table_name, VoltType vt) {
            this.partitions = partitions;
            this.table_name = table_name;
            this.vt = vt;
        }

        public PartitionedTable(List<PartitionRange<T>> partitions, String table_name, VoltType vt, Table catalog_table) {
            this.partitions = partitions;
            this.table_name = table_name;
            this.vt = vt;
            this.catalog_table = catalog_table;
        }

        /**
         * Find the partition for a key
         * 
         * @param id
         * @return the partition id or null partition id if no match could be
         *         found
         */
        @SuppressWarnings("unchecked")
        public int findPartition(Object id) throws Exception {
            if (trace.val) {
                LOG.trace(String.format("Looking up key %s on table %s during phase %s", id, this.table_name));
            }
            assert (id instanceof Number);

            // TODO I am sure there is a better way to do this... Andy? (ae)
            // TODO fix partitiontype
            Long cast_id = ((Number) id).longValue();

            try {
                for (PartitionRange<T> p : this.partitions) {
                    // if this greater than or equal to the min inclusive val
                    // and
                    // less than
                    // max_exclusive or equal to both min and max (singleton)
                    // TODO fix partitiontype
                    if ((p.min_inclusive_long.compareTo(cast_id) <= 0 && p.max_exclusive_long.compareTo(cast_id) > 0)
                            || (p.min_inclusive_long.compareTo(cast_id) == 0 && p.max_exclusive_long.compareTo(cast_id) == 0)) {
                        return p.partition;
                    }
                }
            } catch (Exception e) {
                LOG.error("Error looking up partition", e);
            }

            LOG.error(String.format("Partition not found for ID:%s.  Type:%s  TableType", cast_id, cast_id.getClass().toString(), this.vt.getClass().toString()));
            return HStoreConstants.NULL_PARTITION_ID;
        }

        /**
         * Associate a partition with a set of values in the form of val or
         * val1,val2 or val1-val2 or val1,val2-val3 or some other combination
         * 
         * @param partition_id
         * @param partitionValues
         * @throws ParseException
         */
        public void addPartitionRanges(int partition_id, String partition_values) throws ParseException {
            for (String range : partition_values.split(",")) {
                this.partitions.add(new PartitionRange<T>(this.vt, partition_id, range));
            }
        }

        public Table getCatalog_table() {
            return catalog_table;
        }
    }

    /**
     * A defined range of keys and an associated partition id. Sorts by min id,
     * then max id only first, ie (1-4 < 2-3) and (1-4 < 1-5)
     * 
     * @author aelmore
     * @param <T>
     *            Comparable type of key
     */
    public static class PartitionRange<T extends Comparable<T>> implements Comparable<PartitionRange<T>> {
        protected T min_inclusive;
        protected Long min_inclusive_long;
        protected T max_exclusive;
        protected Long max_exclusive_long;
        protected int partition;
        protected VoltType vt;

        public PartitionRange(VoltType vt, T min_inclusive, T max_exclusive) {
            this.vt = vt;
            if (min_inclusive.compareTo(max_exclusive) > 0) {
                throw new RuntimeException("Min cannot be greater than max. Must be <= ");
            }
            this.min_inclusive = min_inclusive;
            this.max_exclusive = max_exclusive;
            // TODO fix partitiontype
            assert (min_inclusive instanceof Number && max_exclusive instanceof Number);
            LOG.debug("Setting long values");
            this.min_inclusive_long = ((Number) min_inclusive).longValue();
            this.max_exclusive_long = ((Number) max_exclusive).longValue();
            this.partition = -1;
        }

        @SuppressWarnings("unchecked")
        public PartitionRange(VoltType vt, int partition_id, String range) throws ParseException {
            this.vt = vt;
            this.partition = partition_id;

            // TODO add support for open ranges ie -100 (< 100) and 100-
            // (anything >=
            // 100)

            // x-y
            if (range.contains("-")) {
                String vals[] = range.split("-", 2);
                Object min_obj = VoltTypeUtil.getObjectFromString(vt, vals[0]);
                this.min_inclusive = (T) min_obj;
                Object max_obj = VoltTypeUtil.getObjectFromString(vt, vals[1]);
                this.max_exclusive = (T) max_obj;
                if (this.min_inclusive.compareTo(this.max_exclusive) > 0) {
                    throw new ParseException("Min cannot be greater than max", -1);
                }
            }
            // x
            else {
                Object obj = VoltTypeUtil.getObjectFromString(vt, range);
                this.min_inclusive = (T) obj;
                this.max_exclusive = (T) obj;
            }
            // TODO fix partitiontype
            assert (min_inclusive instanceof Number && max_exclusive instanceof Number);
            this.min_inclusive_long = ((Number) min_inclusive).longValue();
            this.max_exclusive_long = ((Number) max_exclusive).longValue();
        }

        public PartitionRange(VoltType vt) {
            this.vt = vt;            
        }

        @Override
        public String toString() {
            return "PartitionRange [" + this.min_inclusive + "-" + this.max_exclusive + ") p_id=" + this.partition + "]";
        }

        @Override
        public int compareTo(PartitionRange<T> o) {
            if (this.min_inclusive.compareTo(o.min_inclusive) < 0) {
                return -1;
            } else if (this.min_inclusive.compareTo(o.min_inclusive) == 0) {
                return (this.max_exclusive.compareTo(o.max_exclusive));
            } else {
                return 1;
            }
        }

        public T getMin_inclusive() {
            return this.min_inclusive;
        }

        public T getMax_exclusive() {
            return this.max_exclusive;
        }

        public VoltType getVt() {
            return this.vt;
        }

    }

    // ********End Containers **************************************/

    /**
     * Update the current partition phase (plan/epoch/etc)
     * 
     * @param new_phase
     * @return The delta between the plans or null if there is no change
     */
    public ReconfigurationPlan setPartitionPhase(String new_phase) {
        String old_phase = this.current_phase;
        if (old_phase != null && old_phase.equals(new_phase)) {
            return null;
        }
        if (this.partition_phase_map.containsKey(new_phase) == false) {
            throw new RuntimeException("Invalid Phase Name: " + new_phase + " phases: " + StringUtil.join(",", this.partition_phase_map.keySet()));
        }
        synchronized (this) {
            this.current_phase = new_phase;
            this.previous_phase = old_phase;
        }
        try {
            if (old_phase == null) {
                return null;
            }
            return new ReconfigurationPlan(this.partition_phase_map.get(old_phase), this.partition_phase_map.get(new_phase));
        } catch (Exception ex) {
            LOG.error("Exception on setting partition phase", ex);
            LOG.error(String.format("Old phase: %s  New Phase: %s" , old_phase,new_phase));
            throw new RuntimeException("Exception building Reconfiguration plan", ex);
        }

    }

    /**
     * @return the current partition phase/epoch
     */
    public synchronized String getCurrent_phase() {
        return this.current_phase;
    }

    /**
     * @return the current partition phase/epoch
     */
    public synchronized String getPreviousPhase_phase() {
        return this.previous_phase;
    }

    /*
     * (non-Javadoc)
     * @see org.json.JSONString#toJSONString()
     */
    @Override
    public String toJSONString() {
        // TODO Auto-generated method stub
        return null;
    }

    /*
     * (non-Javadoc)
     * @see edu.brown.utils.JSONSerializable#save(java.io.File)
     */
    @Override
    public void save(File output_path) throws IOException {
        // TODO Auto-generated method stub

    }

    /*
     * (non-Javadoc)
     * @see edu.brown.utils.JSONSerializable#load(java.io.File,
     * org.voltdb.catalog.Database)
     */
    @Override
    public void load(File input_path, Database catalog_db) throws IOException {
        // TODO Auto-generated method stub

    }

    /*
     * (non-Javadoc)
     * @see edu.brown.utils.JSONSerializable#toJSON(org.json.JSONStringer)
     */
    @Override
    public void toJSON(JSONStringer stringer) throws JSONException {
        // TODO Auto-generated method stub

    }

    /*
     * (non-Javadoc)
     * @see edu.brown.utils.JSONSerializable#fromJSON(org.json.JSONObject,
     * org.voltdb.catalog.Database)
     */
    @Override
    public void fromJSON(JSONObject json_object, Database catalog_db) throws JSONException {
        // TODO Auto-generated method stub

    }

    public Map<String, List<String>> getRelatedTablesMap() {
        return relatedTablesMap;
    }

}
