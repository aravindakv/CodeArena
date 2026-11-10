#!/bin/bash
source /runner/common.sh
mkdir -p /tmp/b
# The root filesystem is read-only, so copy the pre-warmed build cache into the writable /tmp.
cp -r /opt/gocache /tmp/gocache
export GOCACHE=/tmp/gocache GOPATH=/tmp/gopath GOMAXPROCS=2 CGO_ENABLED=0 HOME=/tmp
if ! timeout -s KILL 30 go build -o /tmp/b/sol /work/solution.go /work/harness.go 2> /out/compile.err; then
  compile_failed 1
fi
run_program /tmp/b/sol