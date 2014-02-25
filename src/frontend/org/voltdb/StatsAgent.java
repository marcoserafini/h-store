/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB L.L.C.
 *
 * VoltDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VoltDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.voltdb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import org.apache.log4j.Logger;

import edu.brown.logging.LoggerUtil;
import edu.brown.logging.LoggerUtil.LoggerBoolean;

/**
 * Agent responsible for collecting stats on this host.
 *
 */
public class StatsAgent {
    public static final Logger LOG = Logger.getLogger(StatsAgent.class);
    private static final LoggerBoolean debug = new LoggerBoolean(LOG.isDebugEnabled());
    private static final LoggerBoolean trace = new LoggerBoolean(LOG.isTraceEnabled());
    static {
        LoggerUtil.setupLogging();
        LoggerUtil.attachObserver(LOG, debug, trace);
    }
    private final HashMap<SysProcSelector, HashMap<Integer, ArrayList<StatsSource>>> registeredStatsSources =
        new HashMap<SysProcSelector, HashMap<Integer, ArrayList<StatsSource>>>();

    private final HashSet<SysProcSelector> handledSelectors = new HashSet<SysProcSelector>();

    public StatsAgent() {
        SysProcSelector selectors[] = SysProcSelector.values();
        for (int ii = 0; ii < selectors.length; ii++) {
            registeredStatsSources.put(selectors[ii], new HashMap<Integer, ArrayList<StatsSource>>());
        }
        handledSelectors.add(SysProcSelector.PROCEDURE);
    }

    public synchronized void registerStatsSource(SysProcSelector selector, int catalogId, StatsSource source) {
        assert selector != null;
        assert source != null;
        final HashMap<Integer, ArrayList<StatsSource>> catalogIdToStatsSources = registeredStatsSources.get(selector);
        assert catalogIdToStatsSources != null;
//        LOG.info("Registering");
        ArrayList<StatsSource> statsSources = catalogIdToStatsSources.get(catalogId);
        if (statsSources == null) {
            statsSources = new ArrayList<StatsSource>();
            catalogIdToStatsSources.put(catalogId, statsSources);
        }
        statsSources.add(source);
//        LOG.info("Registered");
    }

    public synchronized VoltTable getStats(
            final SysProcSelector selector,
            final ArrayList<Integer> catalogIds,
            final boolean interval,
            final Long now) {
        assert selector != null;
        assert catalogIds != null;
        assert catalogIds.size() > 0;
        final HashMap<Integer, ArrayList<StatsSource>> catalogIdToStatsSources = registeredStatsSources.get(selector);
        assert catalogIdToStatsSources != null;

        assert(catalogIdToStatsSources.get(catalogIds.get(0)) != null) :
            "Invalid stats source type '" + selector + "'";
        ArrayList<StatsSource> statsSources = catalogIdToStatsSources.get(catalogIds.get(0));
        assert statsSources != null && statsSources.size() > 0;
        final VoltTable.ColumnInfo columns[] = statsSources.get(0).getColumnSchema().toArray(new VoltTable.ColumnInfo[0]);
        final VoltTable resultTable = new VoltTable(columns);

        for (Integer catalogId : catalogIds) {
            statsSources = catalogIdToStatsSources.get(catalogId);
            assert statsSources != null;
            for (final StatsSource ss : statsSources) {
                assert ss != null;
                Object statsRows[][] = ss.getStatsRows(interval, now);
                for (Object[] row : statsRows) {
                    resultTable.addRow(row);
                }
            }
        }
        return resultTable;
    }
}
