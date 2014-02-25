#! /bin/bash
#The file to pull
hevent_file=~/git/h-store/hevent.log

#clean up
rm -rf err
while read line
do
    name=$line
    rm -rf $name
done < hosts.txt

#get files
touch hevent-merge.log
parallel-slurp -h hosts.txt -e err -o out $hevent_file  hevent.log 
while read line
do
    name=$line
    cat $name/hevent.log >> hevent-merge.log 
done < hosts.txt

#merge
sort hevent-merge.log > hevent.log
rm hevent-merge.log

#clean up downloaded individual files
rm -rf out
while read line
do
    name=$line
    rm -rf $name
done < hosts.txt
