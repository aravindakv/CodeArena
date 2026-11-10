#!/usr/bin/env bash
# Builds and runs every backend service as a local process (logs in .local/logs, pids in .local/pids).
#   scripts/services.sh start [svc...]    build jars if needed, start (default: all)
#   scripts/services.sh build             ./gradlew bootJar
#   scripts/services.sh stop [svc...]
#   scripts/services.sh restart [svc...]
#   scripts/services.sh status
#   scripts/services.sh logs <svc>        tail -f the log
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
[[ -f .env ]] && { set -a; source .env; set +a; }
mkdir -p .local/logs .local/pids
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"

# name:port  (port 0 = no HTTP endpoint)
ALL=(auth-service:9081 problem-service:9082 submission-service:9083 contest-service:9084 analytics-service:9085
     api-gateway:9080 judge-worker:0)

port_of() { for e in "${ALL[@]}"; do [[ ${e%%:*} == "$1" ]] && { echo "${e##*:}"; return; }; done; echo "unknown service $1" >&2; exit 1; }
names() { if [[ $# -gt 0 ]]; then echo "$@"; else for e in "${ALL[@]}"; do echo -n "${e%%:*} "; done; fi; }
jar_of() { echo "services/$1/build/libs/$1.jar"; }
running() { [[ -f .local/pids/$1.pid ]] && kill -0 "$(cat .local/pids/$1.pid)" 2>/dev/null; }

build() { ./gradlew bootJar -q; }

start_one() {
  local s=$1 port; port=$(port_of "$s")
  if running "$s"; then echo "  $s already running (pid $(cat .local/pids/$s.pid))"; return; fi
  if [[ $s == judge-worker ]]; then
    docker image inspect codearena/runner-python:1 >/dev/null 2>&1 \
      || echo "  WARNING: runner images missing - run sandbox-images/build-all.sh"
    [[ ${JUDGE_RUNTIME:-runsc} == runsc ]] && ! docker info 2>/dev/null | grep -q runsc \
      && echo "  WARNING: gVisor (runsc) is not registered with Docker; install it or set JUDGE_RUNTIME=runc in .env (dev only)"
  fi
  nohup "$JAVA" -XX:+UseSerialGC -Xmx384m -jar "$(jar_of "$s")" > ".local/logs/$s.log" 2>&1 &
  echo $! > ".local/pids/$s.pid"
  echo "  $s started (pid $!, log .local/logs/$s.log)"
}

wait_healthy() {
  local s=$1 port; port=$(port_of "$s")
  [[ $port == 0 ]] && return 0
  for _ in $(seq 1 90); do
    curl -fs "http://localhost:$port/actuator/health" >/dev/null 2>&1 && { echo "  $s healthy on :$port"; return 0; }
    running "$s" || { echo "  $s exited - see .local/logs/$s.log" >&2; tail -20 ".local/logs/$s.log" >&2; return 1; }
    sleep 1
  done
  echo "  $s not healthy after 90s - see .local/logs/$s.log" >&2; return 1
}

stop_one() {
  local s=$1
  if running "$s"; then kill "$(cat .local/pids/$s.pid)"; echo "  $s stopped"; else echo "  $s not running"; fi
  rm -f ".local/pids/$s.pid"
}

cmd=${1:-status}; shift || true
case $cmd in
  build) build ;;
  start)
    for s in $(names "$@"); do [[ -f $(jar_of "$s") ]] || { echo "==> building jars"; build; break; }; done
    echo "==> starting"
    for s in $(names "$@"); do start_one "$s"; done
    for s in $(names "$@"); do wait_healthy "$s"; done
    echo "API gateway: http://localhost:9080/api/v1/problems" ;;
  stop) for s in $(names "$@"); do stop_one "$s"; done ;;
  restart) for s in $(names "$@"); do stop_one "$s"; done; sleep 2
           build; for s in $(names "$@"); do start_one "$s"; done
           for s in $(names "$@"); do wait_healthy "$s"; done ;;
  status) for e in "${ALL[@]}"; do s=${e%%:*}
            if running "$s"; then echo "  UP    $s (pid $(cat .local/pids/$s.pid), port ${e##*:})"; else echo "  DOWN  $s"; fi; done ;;
  logs) tail -n 100 -f ".local/logs/${1:?service name}.log" ;;
  *) echo "usage: $0 build|start|stop|restart|status|logs [service...]" >&2; exit 2 ;;
esac