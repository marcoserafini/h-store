#!/bin/bash

if [ $# -eq 0 ]
then
	echo ""
	echo "enter site ID (two digits) and partition ID (three digits)"
	echo "the site and the partitions must be local"
	echo "example: ${0} 00 001 checks site 0 and partition 1"
	echo ""
	exit
fi

PID=$(top -n1 | grep -m1 java | perl -pe 's/\e\[?.*?[\@-~] ? ?//g' 2> /dev/null | cut -f1 -d' ')
printf '%d' $(jstack $PID | grep -m1 "H${1}-${2}" | cut -d '=' -f 4 | cut -d ' ' -f 1)