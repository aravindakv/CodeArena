#!/bin/bash
source /runner/common.sh
if ! node --check /work/solution.js 2> /out/compile.err; then
  compile_failed 1
fi
run_program node --max-old-space-size=$((MEMORY_MB * 3 / 4)) /work/harness.js