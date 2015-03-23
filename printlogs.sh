#!/bin/sh

# Outputs the whole log of the last app.
last=`yarn application -list 2>/dev/null | grep -o "^application_[0-9]*_[0-9]*"`
if [ -z "$last" ]
then
	last=`ls -1t $HADOOP_HOME/logs/userlogs/ | head -1`
fi
cat $HADOOP_HOME/logs/userlogs/"$last"/*/*
