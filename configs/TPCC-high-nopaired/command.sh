#!/bin/bash

if [ $# -eq 0 ]
  then
	  echo "need number of the experiment (e.g. 1, 2, ..)"
#    echo "1) subdirectory of configs/ and results/ that define the benchmark (e.g. TPCC-high-nopaired)"
#	echo "2) name of the benchmark (e.g. tpcc)"
#	echo "3) instance of the experiment (e.g. 1, 2, ..)"
#	echo "4) true if want to run monitoring too"
	exit
fi

##### Load configuration #####

sh scripts/load_config.sh configs/TPCC-high-nopaired tpcc

##### Collect monitoring files #####

# generate monitoring files

ant hstore-benchmark -Dproject=tpcc -Dglobal.hasher_plan=plan.json -Dglobal.hasher_class=edu.brown.hashing.TwoTieredRangeHasher -Dsite.txn_restart_limit_sysproc=100 -Dsite.jvm_asserts=false -Dsite.commandlog_enable=false -Dsite.exec_db2_redirects=false -Dsite.exec_early_prepare=false -Dsite.exec_force_singlepartitioned=true -Dsite.markov_fixed=false -Dsite.planner_caching=false -Dsite.reconfig_async_chunk_size_kb=8048 -Dsite.reconfig_async_delay_ms=511 -Dsite.reconfig_chunk_size_kb=10048 -Dsite.reconfig_plan_delay=1511 -Dsite.reconfig_subplan_split=20 -Dsite.specexec_enable=true -Dpartitionplan=tpcc-plan-fine-grained.pplan  -Dclient.duration=60000 -Dclient.interval=1000 -Dclient.txnrate=1000 -Dclient.count=6 -Dclient.hosts="istc9;istc8;istc7;istc6;istc5;istc4" -Dclient.threads_per_host=16 -Dclient.blocking_concurrent=30 -Dclient.output_interval=true -Dsite.planner_caching=false -Dclient.txn_hints=false  -Dclient.skewfactor=0.65  -Dclient.output_results_csv=results.csv -Delastic.run_monitoring=true -Delastic.update_plan=false -Delastic.exec_reconf=false -Delastic.delay=20000

# save monitoring files and logs for this run
cp *partition*.log results/TPCC-high-nopaired/monitor-${1}/
./scripts/save_results.sh results/TPCC-high-nopaired/monitor-${1}

# save_results.sh does the following
# mkdir $1
# cp plan_out.json $1
# cp -r obj/logs $1
# cp results.csv $1
# cp out.log $1
# cp hevent.log $1

##### Graph #####

# load monitoring files
cp results/TPCC-high-nopaired/monitor-${1}/*.log .

# run controller
ant affinity -Dproject=tpcc -Dglobal.hasher_plan=plan.json -Dglobal.hasher_class=edu.brown.hashing.TwoTieredRangeHasher -Delastic.run_monitoring=false -Delastic.update_plan=true -Delastic.exec_reconf=false -Delastic.max_load=5000 -Delastic.algo=graph | tee out.log

# save reconfiguration output
cp out.log results/TPCC-high-nopaired/graph/${1}/out-controller.log

# copy plan_out.json
./scripts/deploy.sh config

# run from plan_out.json
ant hstore-benchmark -Dproject=tpcc -Dglobal.hasher_plan=plan_out.json -Dglobal.hasher_class=edu.brown.hashing.TwoTieredRangeHasher -Dsite.txn_restart_limit_sysproc=100 -Dsite.jvm_asserts=false -Dsite.commandlog_enable=false -Dsite.exec_db2_redirects=false -Dsite.exec_early_prepare=false -Dsite.exec_force_singlepartitioned=true -Dsite.markov_fixed=false -Dsite.planner_caching=false -Dsite.reconfig_async_chunk_size_kb=8048 -Dsite.reconfig_async_delay_ms=511 -Dsite.reconfig_chunk_size_kb=10048 -Dsite.reconfig_plan_delay=1511 -Dsite.reconfig_subplan_split=20 -Dsite.specexec_enable=true -Dpartitionplan=tpcc-plan-fine-grained.pplan  -Dclient.duration=20000 -Dclient.interval=1000 -Dclient.txnrate=1000 -Dclient.count=6 -Dclient.hosts="istc9;istc8;istc7;istc6;istc5;istc4" -Dclient.threads_per_host=16 -Dclient.blocking_concurrent=30 -Dclient.output_interval=true -Dsite.planner_caching=false -Dclient.txn_hints=false  -Dclient.skewfactor=0.65  -Dclient.output_results_csv=results.csv | tee out.log

# save results
./scripts/save_results.sh results/TPCC-high-nopaired/graph/${1}

##### Greedy #####

# load monitoring files
cp results/TPCC-high-nopaired/monitor-${1}/*.log .

# run controller
ant affinity -Dproject=tpcc -Dglobal.hasher_plan=plan.json -Dglobal.hasher_class=edu.brown.hashing.TwoTieredRangeHasher -Delastic.run_monitoring=false -Delastic.update_plan=true -Delastic.exec_reconf=false -Delastic.max_load=1000 -Delastic.algo=greedy-ext -Delastic.root_table=warehouse -Delastic.topk=3 | tee out.log

# save reconfiguration output
cp out.log results/TPCC-high-nopaired/greedy-ext/${1}/out-controller.log

# copy plan_out.json
./scripts/deploy.sh config

# run from plan_out.json
ant hstore-benchmark -Dproject=tpcc -Dglobal.hasher_plan=plan_out.json -Dglobal.hasher_class=edu.brown.hashing.TwoTieredRangeHasher -Dsite.txn_restart_limit_sysproc=100 -Dsite.jvm_asserts=false -Dsite.commandlog_enable=false -Dsite.exec_db2_redirects=false -Dsite.exec_early_prepare=false -Dsite.exec_force_singlepartitioned=true -Dsite.markov_fixed=false -Dsite.planner_caching=false -Dsite.reconfig_async_chunk_size_kb=8048 -Dsite.reconfig_async_delay_ms=511 -Dsite.reconfig_chunk_size_kb=10048 -Dsite.reconfig_plan_delay=1511 -Dsite.reconfig_subplan_split=20 -Dsite.specexec_enable=true -Dpartitionplan=tpcc-plan-fine-grained.pplan  -Dclient.duration=20000 -Dclient.interval=1000 -Dclient.txnrate=1000 -Dclient.count=6 -Dclient.hosts="istc9;istc8;istc7;istc6;istc5;istc4" -Dclient.threads_per_host=16 -Dclient.blocking_concurrent=30 -Dclient.output_interval=true -Dsite.planner_caching=false -Dclient.txn_hints=false  -Dclient.skewfactor=0.65  -Dclient.output_results_csv=results.csv | tee out.log

# save results
./scripts/save_results.sh results/TPCC-high-nopaired/greedy-ext/${1}

##### Metis #####

# load monitoring files
cp results/TPCC-high-nopaired/monitor-${1}/*.log .

# run controller
ant affinity -Dproject=tpcc -Dglobal.hasher_plan=plan.json -Dglobal.hasher_class=edu.brown.hashing.TwoTieredRangeHasher -Delastic.run_monitoring=false -Delastic.update_plan=true -Delastic.exec_reconf=false -Delastic.imbalance_load=0.65 -Delastic.algo=metis | tee out.log

# save reconfiguration output
cp out.log results/TPCC-high-nopaired/metis/${1}/out-controller.log

# copy plan_out.json
./scripts/deploy.sh config

# run from plan_out.json
ant hstore-benchmark -Dproject=tpcc -Dglobal.hasher_plan=plan_out.json -Dglobal.hasher_class=edu.brown.hashing.TwoTieredRangeHasher -Dsite.txn_restart_limit_sysproc=100 -Dsite.jvm_asserts=false -Dsite.commandlog_enable=false -Dsite.exec_db2_redirects=false -Dsite.exec_early_prepare=false -Dsite.exec_force_singlepartitioned=true -Dsite.markov_fixed=false -Dsite.planner_caching=false -Dsite.reconfig_async_chunk_size_kb=8048 -Dsite.reconfig_async_delay_ms=511 -Dsite.reconfig_chunk_size_kb=10048 -Dsite.reconfig_plan_delay=1511 -Dsite.reconfig_subplan_split=20 -Dsite.specexec_enable=true -Dpartitionplan=tpcc-plan-fine-grained.pplan  -Dclient.duration=20000 -Dclient.interval=1000 -Dclient.txnrate=1000 -Dclient.count=6 -Dclient.hosts="istc9;istc8;istc7;istc6;istc5;istc4" -Dclient.threads_per_host=16 -Dclient.blocking_concurrent=30 -Dclient.output_interval=true -Dsite.planner_caching=false -Dclient.txn_hints=false  -Dclient.skewfactor=0.65  -Dclient.output_results_csv=results.csv | tee out.log

# save results
./scripts/save_results.sh results/TPCC-high-nopaired/metis/${1}

