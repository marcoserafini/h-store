#!/bin/bash
## MONITOR ####

for i in {1..9}
do
	ant hstore-benchmark -Dproject=affinity -Dglobal.hasher_plan=plan.json -Dglobal.hasher_class=edu.brown.hashing.TwoTieredRangeHasher -Dnostart=true -Dnoloader=true -Dnoshutdown=true -Dclient.interval=1000 -Dclient.txnrate=1000 -Dclient.count=5 -Dclient.hosts="istc2;istc4;istc5;istc11;istc12" -Dclient.threads_per_host=16 -Dclient.blocking_concurrent=30 -Dclient.output_results_csv=results.csv -Dclient.output_interval=true -Dsite.planner_caching=false -Dclient.txn_hints=false -Dsite.exec_early_prepare=false -Delastic.run_monitoring=true -Delastic.update_plan=false -Delastic.exec_reconf=false -Delastic.delay=20000 -Dclient.duration=60000 | tee -a out.log
	mkdir results/affinity-5GB-hotproduct/monitor-${i}
	cp *partition*.log results/affinity-5GB-hotproduct/monitor-${i}/
	./scripts/save_results.sh results/affinity-5GB-hotproduct/monitor-${i}
	sleep 5m
done

