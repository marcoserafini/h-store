#!/bin/bash

# monitor

for i in {1..9}
do
        ant hstore-benchmark -Dproject=tpcc -Dglobal.hasher_plan=plan.json -Dglobal.hasher_class=edu.brown.hashing.TwoTieredRangeHasher -Dnostart=true -Dnoloader=true -Dnoshutdown=true -Dsite.txn_restart_limit_sysproc=100 -Dsite.jvm_asserts=false -Dsite.commandlog_enable=false -Dsite.exec_db2_redirects=false -Dsite.exec_early_prepare=false -Dsite.exec_force_singlepartitioned=true -Dsite.markov_fixed=false -Dsite.planner_caching=false -Dsite.reconfig_async_chunk_size_kb=8048 -Dsite.reconfig_async_delay_ms=511 -Dsite.reconfig_chunk_size_kb=10048 -Dsite.reconfig_plan_delay=1511 -Dsite.reconfig_subplan_split=20 -Dsite.specexec_enable=true -Dpartitionplan=tpcc-plan-fine-grained.pplan  -Dclient.duration=60000 -Dclient.interval=1000 -Dclient.txnrate=1000 -Dclient.count=6 -Dclient.hosts="istc9;istc8;istc7;istc6;istc5;istc4" -Dclient.threads_per_host=16 -Dclient.blocking_concurrent=30 -Dclient.output_interval=true -Dsite.planner_caching=false -Dclient.txn_hints=false  -Dclient.skewfactor=0.65  -Dclient.output_results_csv=results.csv -Delastic.run_monitoring=true -Delastic.update_plan=false -Delastic.exec_reconf=false -Delastic.delay=20000 | tee out-monitor-${i}.log
        mkdir results/TPCC-high-nopaired/monitor-${i}
        cp *partition*.log results/TPCC-high-nopaired/monitor-${i}/
        ./scripts/save_results.sh results/TPCC-high-nopaired/monitor-${i}
        sleep 5m
done

