#!/usr/bin/env bash
# One place to stop things.
#   scripts/stop.sh services   the Java services (infra and data keep running)
#   scripts/stop.sh infra      services + Docker Compose containers (data volumes kept)
#   scripts/stop.sh wipe       all of that AND delete the data volumes (asks first)
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
COMPOSE=(docker compose -f deploy/compose/docker-compose.yml --profile tools)

case ${1:-} in
  services) scripts/services.sh stop ;;
  infra) scripts/services.sh stop; "${COMPOSE[@]}" down ;;
  wipe)
    read -r -p "Delete ALL local CodeArena data (users, problems, submissions)? [y/N] " ok
    [[ $ok == y || $ok == Y ]] || { echo "cancelled"; exit 0; }
    scripts/services.sh stop; "${COMPOSE[@]}" down -v; rm -rf .local/logs .local/pids
    # leftover sandbox containers, if a judge was killed mid-run
    docker ps -aq --filter "name=^ca-" | xargs -r docker rm -f >/dev/null
    echo "wiped. scripts/up.sh recreates everything." ;;
  *) echo "usage: $0 services|infra|wipe" >&2; exit 2 ;;
esac

echo "ports still in use:"
for p in 9080 9081 9082 9083 9084 9085 9700 9701 9702 9703 9704 5173; do
  (exec 3<>"/dev/tcp/127.0.0.1/$p") 2>/dev/null && { exec 3>&- 3<&-; echo "  :$p"; }
done
true