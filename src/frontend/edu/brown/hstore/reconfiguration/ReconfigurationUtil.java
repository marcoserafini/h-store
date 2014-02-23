package edu.brown.hstore.reconfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.voltdb.VoltTable;
import org.voltdb.VoltTable.ColumnInfo;
import org.voltdb.VoltType;
import org.voltdb.utils.Pair;

import edu.brown.hashing.ReconfigurationPlan;
import edu.brown.hashing.ReconfigurationPlan.ReconfigurationRange;

public class ReconfigurationUtil {
    private static final Logger LOG = Logger.getLogger(ReconfigurationUtil.class);

    public static List<ReconfigurationPlan> naiveSplitReconfigurationPlan(ReconfigurationPlan plan, int numberOfSplits){
        List<ReconfigurationPlan> splitPlans = new ArrayList<>();
        for(int i = 0; i < numberOfSplits; i++){
            splitPlans.add(new ReconfigurationPlan());
        }
        //The list of partition pairs that exchange data
        Set<Pair<Integer, Integer>> migrationPairs = new HashSet<>();
        
        //How we are going to split up the pairs
        Map<Pair<Integer, Integer>, Integer> pairToSplitMapping = new HashMap<>();
        
        //Calculate pairs
        for(Entry<Integer, List<ReconfigurationRange<? extends Comparable<?>>>> entry : plan.getIncoming_ranges().entrySet()){
            Set<Integer> partitionsSendingData = new HashSet<>();
            for(ReconfigurationRange<? extends Comparable<?>> range : entry.getValue()){
                partitionsSendingData.add(range.old_partition);
                migrationPairs.add(new Pair<Integer, Integer>(range.old_partition, range.new_partition));
            }
            LOG.info(String.format("Partition: %s is receiving data from :%s",entry.getKey(),StringUtils.join(partitionsSendingData, ",")));
        }
        LOG.info(String.format("Pairs(%s): %s ",migrationPairs.size(), StringUtils.join(migrationPairs, ",")));

        // Sort the migration pairs to make sure we get the same set of splits each time
        ArrayList<Pair<Integer,Integer>> migrationPairsList = new ArrayList<>();
        migrationPairsList.addAll(migrationPairs);
        Collections.sort(migrationPairsList);
        
        //Split pairs into groups based on numSplits paramt
        int pairCounter = 0;
        for(Pair<Integer,Integer> mPair : migrationPairsList){
            pairToSplitMapping.put(mPair, pairCounter % numberOfSplits);
            pairCounter++;
        }
        
        //put ranges into split rangePLans
        for(Entry<Integer, List<ReconfigurationRange<? extends Comparable<?>>>> entry : plan.getIncoming_ranges().entrySet()){
            for(ReconfigurationRange<? extends Comparable<?>> range : entry.getValue()){
                //find which split this range is going into
                Integer splitIndex = pairToSplitMapping.get(new Pair<Integer,Integer>(range.old_partition, range.new_partition));
                //add it
                splitPlans.get(splitIndex).addRange(range);
            }
        }
        
        //DEBUG
        for (int j = 0; j < numberOfSplits; j++){
            
            Set<Pair<Integer, Integer>> debugSendingData = new HashSet<>();
            for(Entry<Integer, List<ReconfigurationRange<? extends Comparable<?>>>> entry : splitPlans.get(j).getIncoming_ranges().entrySet()){
                for(ReconfigurationRange<? extends Comparable<?>> range : entry.getValue()){
                    debugSendingData.add(new Pair<Integer, Integer>(range.old_partition, range.new_partition));
                }
            }
            LOG.info(String.format("PlanSplit:%s has the pairs(%s): %s", j, debugSendingData.size(), StringUtils.join(debugSendingData,",")));
        }
        
        return splitPlans;
    }

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
