#!/bin/bash
# Shared by every runner image (sourced by /runner/run.sh). Contract with judge-worker:
#   in : /work/<user file>, /work/<harness file>, /work/inputs.jsonl (one JSON test input per line)
#   out: /out/results.jsonl  written by the harness, one JSON result per input line
#        /out/stdout, /out/stderr  the program's own output (capped), shown to the user for debugging
#        /out/compile.err        compiler messages, if compilation failed
#        /out/meta               "COMPILE <exit>"  or  "RUN <exit> <elapsed ms>"
#        /out/maxrss_kb          peak memory of the program (from GNU time)
#   env: TIME_LIMIT_MS (whole test set), MAX_OUT_BYTES, MEMORY_MB
set -u
: "${TIME_LIMIT_MS:=2000}" "${MAX_OUT_BYTES:=65536}" "${MEMORY_MB:=256}"

compile_failed() {
  echo "COMPILE $1" > /out/meta
  exit 0
}

# run_program <command...>: runs it once over all inputs with the time limit.
run_program() {
  local secs start end status
  secs=$(printf '%d.%03d' $((TIME_LIMIT_MS / 1000)) $((TIME_LIMIT_MS % 1000)))
  start=$(date +%s%N)
  /usr/bin/time -f '%M' -o /out/maxrss_kb \
    timeout -s KILL "$secs" "$@" < /work/inputs.jsonl 2> /out/stderr \
    | head -c "$MAX_OUT_BYTES" > /out/stdout
  status=${PIPESTATUS[0]}
  end=$(date +%s%N)
  echo "RUN $status $(( (end - start) / 1000000 ))" > /out/meta
}