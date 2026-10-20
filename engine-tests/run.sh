#!/bin/sh
# Desktop tests for the VPN engine (needs JDK 17+ and: pip install cryptography)
set -e
cd "$(dirname "$0")"
SRC=../app/src/main/java/com/pickixo/vpn/sstp
rm -rf out && mkdir out
javac -d out $SRC/*.java EngineTest.java ParserTest.java
java -cp out com.pickixo.vpn.sstp.EngineTest vectors
java -cp out com.pickixo.vpn.sstp.ParserTest
port=4460
for auth in mschapv2 pap md5; do
  port=$((port+1))
  python3 -W ignore sstp_test_server.py $port $auth > server_$auth.log 2>&1 &
  sleep 2
  java -cp out com.pickixo.vpn.sstp.EngineTest $auth $port | tail -1
  wait
  echo "server checks failed ($auth): $(grep -c FAIL server_$auth.log || true)"
done
