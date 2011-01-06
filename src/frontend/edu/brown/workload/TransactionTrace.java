/***************************************************************************
 *   Copyright (C) 2009 by H-Store Project                                 *
 *   Brown University                                                      *
 *   Massachusetts Institute of Technology                                 *
 *   Yale University                                                       *
 *                                                                         *
 *   Permission is hereby granted, free of charge, to any person obtaining *
 *   a copy of this software and associated documentation files (the       *
 *   "Software"), to deal in the Software without restriction, including   *
 *   without limitation the rights to use, copy, modify, merge, publish,   *
 *   distribute, sublicense, and/or sell copies of the Software, and to    *
 *   permit persons to whom the Software is furnished to do so, subject to *
 *   the following conditions:                                             *
 *                                                                         *
 *   The above copyright notice and this permission notice shall be        *
 *   included in all copies or substantial portions of the Software.       *
 *                                                                         *
 *   THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,       *
 *   EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF    *
 *   MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.*
 *   IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR     *
 *   OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, *
 *   ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR *
 *   OTHER DEALINGS IN THE SOFTWARE.                                       *
 ***************************************************************************/
package edu.brown.workload;

import java.util.*;

import org.apache.commons.collections15.OrderedMap;
import org.apache.commons.collections15.map.LinkedMap;
import org.json.*;
import org.voltdb.VoltType;
import org.voltdb.catalog.*;

import edu.brown.catalog.CatalogUtil;
import edu.brown.utils.ClassUtil;
import edu.brown.utils.StringUtil;

/**
 * 
 * @author Andy Pavlo <pavlo@cs.brown.edu>
 *
 */
public class TransactionTrace extends AbstractTraceElement<Procedure> {
    public enum Members {
        TXN_ID,
        QUERIES
    };
    
    private long txn_id;
    private List<QueryTrace> queries = new ArrayList<QueryTrace>(); 
    private transient LinkedMap<Integer, List<QueryTrace>> query_batches = new LinkedMap<Integer, List<QueryTrace>>();
    
    public TransactionTrace() {
        super();
    }
    
    public TransactionTrace(long xact_id, Procedure catalog_proc, Object params[]) {
        super(catalog_proc, params);
        this.txn_id = xact_id;
    }

    /**
     * Return the TransactionId for this TransactionTrace
     * @return the xact_id
     */
    public long getTransactionId() {
        return this.txn_id;
    }
    
    @Override
    public Procedure getCatalogItem(Database catalog_db) {
        assert(catalog_db != null);
        return (catalog_db.getProcedures().get(this.catalog_item_name));
    }
    
    public void addQuery(QueryTrace query) {
        this.queries.add(query);
        if (!this.query_batches.containsKey(query.getBatchId())) {
            this.query_batches.put(query.getBatchId(), new ArrayList<QueryTrace>());
        }
        this.query_batches.get((Integer)query.getBatchId()).add(query);
    }
    
    @Override
    public String debug(Database catalog_db) {
        final Procedure catalog_proc = this.getCatalogItem(catalog_db);
        final String thick_line = StringUtil.DOUBLE_LINE;
        final String thin_line  = StringUtil.SINGLE_LINE;
        
        // Header Info
        StringBuilder sb = new StringBuilder();
        sb.append(thick_line)
          .append(catalog_proc.getName().toUpperCase() + " - Txn#" + this.txn_id + " - Trace#" + this.id + "\n")
          .append("Start Time:   " + this.start_timestamp + "\n")
          .append("Stop Time:    " + this.stop_timestamp + "\n")
          .append("Run Time:     " + (this.stop_timestamp - this.start_timestamp) + "\n")
          .append("Txn Aborted:  " + this.aborted + "\n")
          .append("# of Queries: " + this.queries.size() + "\n")
          .append("# of Batches: " + this.query_batches.size() + "\n");
        
        // Params
        sb.append("Transasction Parameters: [" + this.params.length + "]\n");
        for (int i = 0; i < this.params.length; i++) {
            ProcParameter catalog_param = catalog_proc.getParameters().get(i);
            Object param = this.params[i];
            String type_name = VoltType.get(catalog_param.getType()).name();
            if (ClassUtil.isArray(param)) type_name += "[" + ((Object[])param).length + "]";
            
            sb.append("   [" + i + "] -> ")
              .append(String.format("%-11s ", "(" + type_name + ")"))
              .append(ClassUtil.isArray(param) ? Arrays.toString((Object[])param) : param)
              .append("\n");
        } // FOR
         
        // Queries
        sb.append(thin_line);
        int ctr = 0;
        for (Integer batch_id : this.query_batches.keySet()) {
            sb.append("Batch #" + batch_id + " (" + this.query_batches.get(batch_id).size() + ")\n");
            for (QueryTrace query : this.query_batches.get(batch_id)) {
                sb.append("   [" + (ctr++) + "] " + query.debug(catalog_db) + "\n");
            } // FOR
        } // FOR
        sb.append(thin_line);
        
        return (sb.toString());
    }

    public Map<Statement, Integer> getStatementCounts(Database catalog_db) {
        Map<Statement, Integer> counts = new HashMap<Statement, Integer>();
        Procedure catalog_proc = this.getCatalogItem(catalog_db);
        for (Statement stmt : catalog_proc.getStatements()) {
            counts.put(stmt, 0);
        }
        for (QueryTrace query : this.queries) {
            Statement stmt = query.getCatalogItem(catalog_db);
            assert(stmt != null) : "Invalid query name '" + query.getCatalogItemName() + "' for " + catalog_proc;
            assert(counts.containsKey(stmt)) : "Unexpected " + CatalogUtil.getDisplayName(stmt) + " in " + catalog_proc;
            counts.put(stmt, counts.get(stmt) + 1);
        }
        return (counts);
    }
    
    /**
     * @return the queries
     */
    public List<QueryTrace> getQueries() {
        return this.queries;
    }
    
    public int getQueryCount() {
        return (this.queries.size());
    }
    
    public QueryTrace getQuery(long id) {
        for (QueryTrace query_trace : this.queries) {
            if (query_trace.id == id) return (query_trace);
        }
        return (null);
    }
    
    /**
     * Returns an ordered set of query batch ids for this Transaction
     * @return
     */
    public List<Integer> getQueryBatchIds() {
        return (this.query_batches.asList());
    }
    
    /**
     * Return a mapping of batch ids to a list of QueryTrace elements
     * @return
     */
    public OrderedMap<Integer, List<QueryTrace>> getQueryBatches() {
        return (this.query_batches);
    }
    
    public void toJSONString(JSONStringer stringer, Database catalog_db) throws JSONException {
        super.toJSONString(stringer, catalog_db);
        stringer.key(Members.TXN_ID.name()).value(this.txn_id);

        stringer.key(Members.QUERIES.name()).array();
        for (QueryTrace query : this.queries) {
            stringer.object();
            query.toJSONString(stringer, catalog_db);
            stringer.endObject();
        } // FOR
        stringer.endArray();
    }
    
    @Override
    protected void fromJSONObject(JSONObject object, Database db) throws JSONException {
        super.fromJSONObject(object, db);
        this.txn_id = object.getLong(Members.TXN_ID.name());
        Procedure catalog_proc = (Procedure)db.getProcedures().get(this.catalog_item_name);
        try {
            super.paramsFromJSONObject(object, catalog_proc.getParameters(), "type");
        } catch (Exception ex) {
            throw new JSONException(ex);
        }
        
        JSONArray jsonQueries = object.getJSONArray(Members.QUERIES.name());
        for (int i = 0; i < jsonQueries.length(); i++) {
            JSONObject jsonQuery = jsonQueries.getJSONObject(i);
            if (jsonQuery.isNull(AbstractTraceElement.Members.NAME.name())) {
                LOG.warn("The catalog name is null for Query #" + i + " in " + this + ". Ignoring...");
                continue;
            }
            try {
                QueryTrace query = QueryTrace.loadFromJSONObject(jsonQuery, db);
                this.addQuery(query);
            } catch (JSONException ex) {
                LOG.fatal("Failed to load query trace #" + i + " for transaction record on " + this.catalog_item_name + " [ID=" + this.id + "]");
                throw ex;
            }
        } // FOR
    } 
    
    public static TransactionTrace loadFromJSONObject(JSONObject object, Database db) throws JSONException {
        TransactionTrace xact = new TransactionTrace();
        xact.fromJSONObject(object, db);
        return (xact);
    }

} // END CLASS