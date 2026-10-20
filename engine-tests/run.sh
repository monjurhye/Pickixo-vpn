#!/bin/sh
# Desktop tests for the VPN engine (needs JDK 17+ and: pip install cryptography)
set -e
cd "$(dirname "$0")"
SRC=../app/src/main/java/com/pickixo/vpn/sstp
rm -rf out && mkdir out
javac -d out $SRC/*.java EngineTest.java ParserTest.java
java -cp out com.pickixo.vpn.sstp.EngineTest vectors
java -cp out com.pickixo.vpn.sstp.ParserTest | tee out/parser.txt
grep -q "PARSER PASS" out/parser.txt
port=4460
for auth in mschapv2 pap md5; do
  port=$((port+1))
  log=server_$auth.log
  python3 -W ignore sstp_test_server.py $port $auth > $log 2>&1 &
  srv=$!
  i=0
  until grep -q READY $log; do
    i=$((i+1))
    if [ $i -gt 60 ] || ! kill -0 $srv 2>/dev/null; then echo "test server failed to start:"; cat $log; exit 1; fi
    sleep 0.5
  done
  java -cp out com.pickixo.vpn.sstp.EngineTest $auth $port
  wait $srv || true
  if grep -q FAIL $log; then echo "server-side checks failed ($auth):"; grep FAIL $log; exit 1; fi
  echo "server-side checks passed ($auth): $(grep -c PASS $log)"
done
echo "ENGINE TESTS PASSED"
