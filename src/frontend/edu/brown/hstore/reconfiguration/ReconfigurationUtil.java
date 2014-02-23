package edu.brown.hstore.reconfiguration;

import java.util.List;

import org.voltdb.VoltTable;
import org.voltdb.VoltTable.ColumnInfo;
import org.voltdb.VoltType;

import edu.brown.hashing.ReconfigurationPlan.ReconfigurationRange;

public class ReconfigurationUtil {

    public static VoltTable getExtractVoltTable(ReconfigurationRange range) {
        ColumnInfo extractTableColumns[] = new ColumnInfo[4];

        extractTableColumns[0] = new ColumnInfo("TABLE_NAME", VoltType.INTEGER);
        extractTableColumns[1] = new ColumnInfo("KEY_TYPE", VoltType.INTEGER);
        extractTableColumns[2] = new ColumnInfo("MIN_INCLUSIVE", VoltType.BIGINT); // range.getVt());
        extractTableColumns[3] = new ColumnInfo("MAX_EXCLUSIVE", VoltType.BIGINT);// range.getVt());

        VoltTable vt = new VoltTable(extractTableColumns);
        // vt.addRow(range.table_name,range.getVt().toString(),range.getMin_inclusive(),range.getMax_exclusive());
        
        if(range.isSingleRange()) {
        	vt.addRow(1, 1, range.getMin_inclusive(), range.getMax_exclusive());
        }
        else {
        	for (int i = 0; i < range.getMinList().size(); i++) {
                vt.addRow(1, 1, range.getMinList().get(i), range.getMaxList().get(i));
            }
        }

        return vt;
    }

    public static VoltTable getExtractVoltTable(List<Long> minInclusives, List<Long> maxExclusives) {
        if(minInclusives.size()!=maxExclusives.size()){
            throw new RuntimeException("Min inclusive list different size than maxExclusives.");
        }
        ColumnInfo extractTableColumns[] = new ColumnInfo[4];

        extractTableColumns[0] = new ColumnInfo("TABLE_NAME", VoltType.INTEGER);
        extractTableColumns[1] = new ColumnInfo("KEY_TYPE", VoltType.INTEGER);
        extractTableColumns[2] = new ColumnInfo("MIN_INCLUSIVE", VoltType.BIGINT); // range.getVt());
        extractTableColumns[3] = new ColumnInfo("MAX_EXCLUSIVE", VoltType.BIGINT);// range.getVt());

        VoltTable vt = new VoltTable(extractTableColumns);
        for (int i = 0; i < minInclusives.size(); i++) {
            vt.addRow(1, 1, minInclusives.get(i), maxExclusives.get(i));
        }

        return vt;       
    }
    
    public static VoltTable getExtractVoltTable(List<ReconfigurationRange> ranges) {
        ColumnInfo extractTableColumns[] = new ColumnInfo[4];

        extractTableColumns[0] = new ColumnInfo("TABLE_NAME", VoltType.INTEGER);
        extractTableColumns[1] = new ColumnInfo("KEY_TYPE", VoltType.INTEGER);
        extractTableColumns[2] = new ColumnInfo("MIN_INCLUSIVE", VoltType.BIGINT); // range.getVt());
        extractTableColumns[3] = new ColumnInfo("MAX_EXCLUSIVE", VoltType.BIGINT);// range.getVt());

        VoltTable vt = new VoltTable(extractTableColumns);
        // vt.addRow(range.table_name,range.getVt().toString(),range.getMin_inclusive(),range.getMax_exclusive());
        for (ReconfigurationRange range : ranges) {
            if(range.isSingleRange()) {
            	vt.addRow(1, 1, range.getMin_inclusive(), range.getMax_exclusive());
            }
            else {
            	for (int i = 0; i < range.getMinList().size(); i++) {
                    vt.addRow(1, 1, range.getMinList().get(i), range.getMaxList().get(i));
                }
            }
        }

        return vt;
    }
}
