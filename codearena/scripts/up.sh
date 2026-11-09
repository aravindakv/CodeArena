#!/usr/bin/env bash
# Starts the local infrastructure and prepares it: tables, buckets, JWT key, seed problems.
# Idempotent: safe to run again at any time.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
COMPOSE=(docker compose -f deploy/compose/docker-compose.yml)

[[ -f .env ]] || { cp .env.example .env; echo "created .env from .env.example"; }
set -a; source .env; set +a

echo "==> starting infrastructure"
"${COMPOSE[@]}" ${TOOLS:+--profile tools} up -d

wait_port() {   # host port name
  for _ in $(seq 1 60); do
    (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null && { exec 3>&- 3<&-; echo "    $2 is up on :$1"; return 0; }
    sleep 1
  done
  echo "    $2 did not come up on :$1 - check: ${COMPOSE[*]} logs $2" >&2; return 1
}
wait_port 9700 dynamodb; wait_port 9701 redis; wait_port 9702 sqs; wait_port 9703 s3; wait_port 9704 pgvector

echo "==> JWT signing key"
mkdir -p .local
if [[ ! -f .local/jwt-private.pem ]]; then
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out .local/jwt-private.pem 2>/dev/null
  chmod 600 .local/jwt-private.pem
  echo "    created .local/jwt-private.pem"
else
  echo "    .local/jwt-private.pem exists"
fi

echo "==> tables, buckets, queues"
scripts/py.sh scripts/bootstrap.py

echo "==> seed problems"
if [[ -f scripts/seed.py && -d seed/problems ]]; then
  scripts/py.sh scripts/seed.py
else
  echo "    skipped: scripts/seed.py and seed/problems arrive in chapter 06"
fi

cat <<MSG

Infrastructure ready. Consoles: SQS http://localhost:9707   (TOOLS=1 scripts/up.sh adds DynamoDB :9705, Redis :9706)
Next:
  sandbox-images/build-all.sh          # once: build the code runner images
  scripts/services.sh start            # build and start all services
  cd frontend && npm install && npm run dev   # web UI on http://localhost:5173
MSG