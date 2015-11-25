#!/bin/bash

# load

ant hstore-benchmark -Dproject=tpcc -Dglobal.hasher_plan=plan.json -Dglobal.hasher_class=edu.brown.hashing.TwoTieredRangeHasher -Dnoshutdown=true -Dnoexecute=true -Dsite.txn_restart_limit_sysproc=100 -Dsite.jvm_asserts=false -Dsite.commandlog_enable=false -Dsite.exec_db2_redirects=false -Dsite.exec_early_prepare=false -Dsite.exec_force_singlepartitioned=true -Dsite.markov_fixed=false -Dsite.planner_caching=false -Dsite.reconfig_async_chunk_size_kb=8048 -Dsite.reconfig_async_delay_ms=511 -Dsite.reconfig_chunk_size_kb=10048 -Dsite.reconfig_plan_delay=1511 -Dsite.reconfig_subplan_split=20 -Dsite.specexec_enable=true -Dpartitionplan=tpcc-plan-fine-grained.pplan -Dsite.planner_caching=false | tee out-load.log
