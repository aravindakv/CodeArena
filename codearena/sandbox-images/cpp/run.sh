#!/bin/bash
source /runner/common.sh
mkdir -p /tmp/b
# main.cpp (the harness) #includes solution.cpp, so this compiles both.
if ! timeout -s KILL 30 g++ -O2 -std=gnu++20 -pipe -o /tmp/b/sol /work/main.cpp 2> /out/compile.err; then
  compile_failed 1
fi
run_program /tmp/b/sol