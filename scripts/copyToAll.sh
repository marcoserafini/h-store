#!/bin/bash
# copies a specific file (given as argument) to all other servers

for s in da01 da02 da04 da05 da06 da07 da08 da09 da10 da11 da12 da14 da15
do scp $1 $s:/localdisk/mserafini/h-store/$1
done
