#!/bin/sh
mvn clean -f ./master
mvn verify -am -f ./master -pl :client -Djavafx.platform=win   -Dctbrec.distibution=minimal
mvn verify -am -f ./master -pl :client -Djavafx.platform=linux -Dctbrec.distibution=minimal
mvn verify -am -f ./master -pl :client -Djavafx.platform=mac   -Dctbrec.distibution=minimal
mvn verify -am -f ./master -pl :server
