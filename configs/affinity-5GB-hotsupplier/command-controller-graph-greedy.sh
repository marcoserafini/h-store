# this was run using monitor-2 data

ant affinity -Dproject=affinity -Dglobal.hasher_plan=plan.json -Dglobal.hasher_class=edu.brown.hashing.TwoTieredRangeHasher -Delastic.run_monitoring=false -Delastic.update_plan=true -Delastic.exec_reconf=false -Delastic.max_load=3700 -Delastic.algo=graph -Dclient.memory=4096 -Delastic.max_partitions_added=6 | tee out.log
