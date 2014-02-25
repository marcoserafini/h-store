/**
 * 
 */
package org.voltdb.sysprocs;

import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.voltdb.DependencySet;
import org.voltdb.ParameterSet;
import org.voltdb.ProcInfo;
import org.voltdb.VoltSystemProcedure;
import org.voltdb.VoltTable;
import org.voltdb.VoltTable.ColumnInfo;
import org.voltdb.VoltType;
import org.voltdb.exceptions.ServerFaultException;
import org.voltdb.utils.VoltTableUtil;

import edu.brown.hstore.PartitionExecutor.SystemProcedureExecutionContext;
import edu.brown.hstore.reconfiguration.ReconfigurationConstants.ReconfigurationProtocols;
import edu.brown.hstore.reconfiguration.ReconfigurationCoordinator.ReconfigurationState;
import edu.brown.utils.FileUtil;

/**
 * Initiate a reconfiguration
 * 
 * @author aelmore
 * 
 */
@ProcInfo(singlePartition = false)
public class ReconfigurationRemote extends VoltSystemProcedure {

  private static final Logger LOG = Logger.getLogger(ReconfigurationRemote.class);

  public static final ColumnInfo nodeResultsColumns[] = { new ColumnInfo("SITE", VoltType.INTEGER) };

  /*
   * (non-Javadoc)
   * 
   * @see org.voltdb.VoltSystemProcedure#initImpl()
   */
  @Override
  public void initImpl() {
    executor.registerPlanFragment(SysProcFragmentId.PF_reconfigurationRemoteDistribute, this);
    executor.registerPlanFragment(SysProcFragmentId.PF_reconfigurationRemoteAggregate, this);
  }

  /*
   * (non-Javadoc)
   * 
   * @see org.voltdb.VoltSystemProcedure#executePlanFragment(java.lang.Long,
   * java.util.Map, int, org.voltdb.ParameterSet,
   * edu.brown.hstore.PartitionExecutor.SystemProcedureExecutionContext)
   */
  @Override
  public DependencySet executePlanFragment(Long txn_id, Map<Integer, List<VoltTable>> dependencies, int fragmentId, ParameterSet params,
      SystemProcedureExecutionContext context) {
    DependencySet result = null;
    int coordinator = (int) params.toArray()[0];
    String partition_plan = (String) params.toArray()[1];
    String reconfiguration_protocol_string = (String) params.toArray()[2];        
    ReconfigurationProtocols reconfig_protocol = ReconfigurationProtocols.valueOf(reconfiguration_protocol_string.toUpperCase());
    int currentPartitionId = context.getPartitionExecutor().getPartitionId();
    switch (fragmentId) {

    case SysProcFragmentId.PF_reconfigurationRemoteDistribute: {
      try {
        hstore_site.getReconfigurationCoordinator().initReconfiguration(coordinator, reconfig_protocol, partition_plan, currentPartitionId);
      } catch (Exception ex) {
        throw new ServerFaultException(ex.getMessage(), txn_id);
      }

      VoltTable vt = new VoltTable(nodeResultsColumns);

      vt.addRow(hstore_site.getSiteId());

      result = new DependencySet(SysProcFragmentId.PF_reconfigurationRemoteDistribute, vt);
      break;
    }
    case SysProcFragmentId.PF_reconfigurationRemoteAggregate: {
      LOG.info("Combining results");
      try {
          hstore_site.getReconfigurationCoordinator().reconfigurationSysProcTerminate();
        } catch (Exception ex) {
          throw new ServerFaultException(ex.getMessage(), txn_id);
        }
      List<VoltTable> siteResults = dependencies.get(SysProcFragmentId.PF_reconfigurationRemoteDistribute);
      if (siteResults == null || siteResults.isEmpty()) {
        String msg = "Missing site results";
        throw new ServerFaultException(msg, txn_id);
      }

      VoltTable vt = VoltTableUtil.union(siteResults);
      if (reconfig_protocol == ReconfigurationProtocols.STOPCOPY){
          FileUtil.appendEventToFile("RECONFIGURATION_" + ReconfigurationState.END.toString());
      }
      result = new DependencySet(SysProcFragmentId.PF_reconfigurationRemoteAggregate, vt);
      break;
    }
    default:
      String msg = "Unexpected sysproc fragmentId '" + fragmentId + "'";
      throw new ServerFaultException(msg, txn_id);

    }

    return (result);
  }

  public VoltTable[] run(int coordinator, String partition_plan, String protocol) {
    String msg = String.format("Coordinator=%s, NewPartitionPlan=%s, Protocol=%s",coordinator, partition_plan,
            protocol);
    FileUtil.appendEventToFile(String.format("RECONFIGURATION_SYSPROC, %s",msg));
    LOG.info(String.format("RUN : Init reconfiguration. %s",msg));
    ParameterSet params = new ParameterSet();

    params.setParameters(coordinator, partition_plan, protocol);
    return this.executeOncePerSite(SysProcFragmentId.PF_reconfigurationRemoteDistribute, SysProcFragmentId.PF_reconfigurationRemoteAggregate,
        params);
  }
}
