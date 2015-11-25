# this was run on monitor-2 data

ant affinity -Dproject=affinity -Dglobal.hasher_plan=plan.json -Dglobal.hasher_class=edu.brown.hashing.TwoTieredRangeHasher -Delastic.run_monitoring=false -Delastic.update_plan=true -Delastic.exec_reconf=false -Delastic.max_load=4050 -Delastic.algo=greedy-ext -Delastic.max_partitions_added=6 -Delastic.topk=8000 -Dclient.memory=4096 | tee out.log
