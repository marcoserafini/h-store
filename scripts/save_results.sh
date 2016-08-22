#!/bin/bash

mkdir $1
cp plan.json $1
cp plan_out.json $1
cp -r obj/logs $1-logs
cp results.csv $1
cp out.log $1
cp out-controller.log $1
cp out-load.log $1
cp hevent.log $1
