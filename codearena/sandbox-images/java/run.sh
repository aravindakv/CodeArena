#!/bin/bash
source /runner/common.sh
mkdir -p /tmp/b
if ! timeout -s KILL 20 javac -J-Xmx256m -J-XX:-UsePerfData -encoding UTF-8 -cp '/opt/jackson/*' \
     -d /tmp/b /work/Solution.java /work/Main.java 2> /out/compile.err; then
  compile_failed 1
fi
run_program java -Xmx$((MEMORY_MB * 3 / 4))m -Xss64m -XX:+UseSerialGC -XX:-UsePerfData \
  -cp "/tmp/b:/opt/jackson/*" Main