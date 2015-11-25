

ant affinity -Dproject=affinity -Dglobal.hasher_plan=plan.json -Dglobal.hasher_class=edu.brown.hashing.TwoTieredRangeHasher -Delastic.run_monitoring=false -Delastic.update_plan=true -Delastic.exec_reconf=false -Delastic.max_load=4200 -Delastic.algo=greedy-ext -Delastic.max_partitions_added=6 -Delastic.topk=8000 | tee out.log

