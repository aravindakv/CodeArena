# 02 · Local infrastructure with Docker Compose

Everything the services need, in containers, with data kept in volumes:

| AWS service (later) | Local stand-in | Port |
| --- | --- | --- |
| DynamoDB | DynamoDB Local (official Amazon image) | 9700 |
| ElastiCache Redis | Redis 7.4 | 9701 |
| SQS | ElasticMQ (SQS-compatible API) | 9702 (console 9707) |
| S3 | Adobe S3Mock (S3-compatible API) | 9703 |
| RDS PostgreSQL + pgvector | pgvector/pgvector | 9704 |
| EventBridge | two SQS queues that the judge sends every verdict to | — |

The services use the normal AWS SDK and only switch endpoints, so moving to AWS later is configuration, not code.

## Step 1: Compose file and queues

**File:** `deploy/compose/docker-compose.yml`

```yaml
# CodeArena local infrastructure. Every port is bound to 127.0.0.1 only.
#   docker compose -f deploy/compose/docker-compose.yml up -d                 # core
#   docker compose -f deploy/compose/docker-compose.yml --profile tools up -d # + browser consoles
# Ports 97xx are used so nothing clashes with other local stacks (e.g. QuickBite).
name: codearena

services:
  dynamodb:                       # DynamoDB-compatible, data kept in a volume
    image: amazon/dynamodb-local:latest
    user: root                    # the image's default user cannot write to a named volume
    command: "-jar DynamoDBLocal.jar -sharedDb -dbPath /data"
    volumes: [ "ddb-data:/data" ]
    ports: [ "127.0.0.1:9700:8000" ]
    restart: unless-stopped

  redis:
    image: redis:7.4-alpine
    command: [ "redis-server", "--appendonly", "yes" ]
    volumes: [ "redis-data:/data" ]
    ports: [ "127.0.0.1:9701:6379" ]
    restart: unless-stopped
    healthcheck:
      test: [ "CMD", "redis-cli", "ping" ]
      interval: 5s
      retries: 10

  sqs:                            # ElasticMQ: SQS-compatible API, queues pre-created from elasticmq.conf
    image: softwaremill/elasticmq-native:latest
    volumes: [ "./elasticmq.conf:/opt/elasticmq.conf:ro" ]
    ports: [ "127.0.0.1:9702:9324" ]
    restart: unless-stopped

  sqs-ui:                         # ElasticMQ web console (a separate image since the UI left elasticmq-native)
    image: softwaremill/elasticmq-ui:latest
    environment:
      SQS_ENDPOINT: http://sqs:9324
      AWS_REGION: ap-south-1
      AWS_ACCESS_KEY_ID: local
      AWS_SECRET_ACCESS_KEY: local
    ports: [ "127.0.0.1:9707:3000" ]
    depends_on: [ sqs ]
    restart: unless-stopped

  s3:                             # Adobe S3Mock: S3-compatible API, files kept in a volume
    image: adobe/s3mock:5.2.3     # pinned: 5.x changed env names and storage format; 4.x data is not readable
    user: root                    # since 5.2 the image runs as a non-root user; without this a named volume is
                                  # not writable and every S3 call returns 500 InternalError
    environment:
      COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS: codearena-tests,codearena-submissions
      COM_ADOBE_TESTING_S3MOCK_STORE_RETAIN_FILES_ON_EXIT: "true"
      COM_ADOBE_TESTING_S3MOCK_STORE_ROOT: /s3mockroot
    volumes: [ "s3-data:/s3mockroot" ]
    ports: [ "127.0.0.1:9703:9090" ]
    restart: unless-stopped

  pgvector:                       # vector store for the AI + RAG ingestion pipeline
    image: pgvector/pgvector:pg16
    environment:
      POSTGRES_USER: codearena
      POSTGRES_PASSWORD: codearena
      POSTGRES_DB: codearena
    volumes: [ "pg-data:/var/lib/postgresql/data" ]
    ports: [ "127.0.0.1:9704:5432" ]
    restart: unless-stopped
    healthcheck:
      test: [ "CMD-SHELL", "pg_isready -U codearena" ]
      interval: 5s
      retries: 10

  # ---- optional consoles: --profile tools ----
  dynamodb-admin:
    image: aaronshaf/dynamodb-admin:latest
    profiles: [ tools ]
    environment:
      DYNAMO_ENDPOINT: http://dynamodb:8000
      AWS_REGION: ap-south-1
      AWS_ACCESS_KEY_ID: local
      AWS_SECRET_ACCESS_KEY: local
    ports: [ "127.0.0.1:9705:8001" ]

  redisinsight:
    image: redis/redisinsight:latest
    profiles: [ tools ]
    ports: [ "127.0.0.1:9706:5540" ]

volumes:
  ddb-data:
  redis-data:
  s3-data:
  pg-data:
```

ElasticMQ creates the queues at start-up, including dead-letter queues: a message that fails three times moves to
the DLQ instead of looping forever (one of the fault-tolerance pieces of chapter 09).

**File:** `deploy/compose/elasticmq.conf`

```hocon
include classpath("application.conf")

# Queue URLs handed to clients must be reachable from the host, hence port 9702.
node-address {
  protocol = http
  host = localhost
  port = 9702
  context-path = ""
}

rest-sqs {
  enabled = true
  bind-port = 9324
  bind-hostname = "0.0.0.0"
  sqs-limits = strict
}

queues {
  # User code submissions. Contest traffic has its own queue so fest users never wait behind practice.
  contest-submissions {
    defaultVisibilityTimeout = 60 seconds
    receiveMessageWait = 20 seconds
    deadLettersQueue { name = "submissions-dlq", maxReceiveCount = 3 }
  }
  practice-submissions {
    defaultVisibilityTimeout = 60 seconds
    receiveMessageWait = 20 seconds
    deadLettersQueue { name = "submissions-dlq", maxReceiveCount = 3 }
  }
  submissions-dlq { }

  # Verdict fan-out (stands in for EventBridge locally): the judge sends every verdict to both.
  verdict-events {
    defaultVisibilityTimeout = 30 seconds
    deadLettersQueue { name = "events-dlq", maxReceiveCount = 5 }
  }
  contest-verdicts {
    defaultVisibilityTimeout = 30 seconds
    deadLettersQueue { name = "events-dlq", maxReceiveCount = 5 }
  }
  events-dlq { }

  # Admin AI + RAG ingestion jobs (one message per seed)
  ingestion-jobs {
    defaultVisibilityTimeout = 900 seconds
    receiveMessageWait = 20 seconds
    deadLettersQueue { name = "ingestion-dlq", maxReceiveCount = 2 }
  }
  ingestion-dlq { }
}
```

## Step 2: the data model

All tables use a generic `PK` / `SK` key pair, so one table can hold several item types (single-table style per
service). TTL attributes make DynamoDB delete expiring items on its own.

| Table | Items (PK / SK) | Written by |
| --- | --- | --- |
| Users | `USER#id / PROFILE`, `EMAIL#email / PROFILE` (uniqueness guard) | auth |
| Problems | `PROBLEM#slug / META`, `COUNTER / PROBLEM_NUMBER`; GSI `byNumber` (`listPk`, `number`) | problem, seed, ingestion, analytics counters |
| Submissions | `USER#id / SUB#ulid` (TTL `ttl` for RUN mode) | submission, judge |
| Contests | `CONTEST#id / META`, `/ REG#user`, `/ RANK#000001` | contest |
| UserStats | `USER#id / STATS`, `/ DAY#date`, `/ PREFS` | analytics |
| UserSolved | `USER#id / PROBLEM#slug` | analytics |
| AuditLog | `AUDIT#date / timestamp#actor` | auth, problem (admin actions) |
| IngestionRuns | `RUN#id / META`, `/ SEED#nnn` (TTL) | problem, ingestion worker |

## Step 3: scripts

`scripts/py.sh` runs a helper script with its Python dependencies (via `uv`, or a private venv).

**File:** `scripts/py.sh`

```bash
#!/usr/bin/env bash
# Run a helper Python script with its dependencies, without touching your system Python.
#   scripts/py.sh scripts/bootstrap.py
# Uses `uv` if installed (recommended: curl -LsSf https://astral.sh/uv/install.sh | sh),
# otherwise a virtualenv in .local/venv.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEPS=(boto3 requests)

# Load .env if present so scripts see the same endpoints as the services.
if [[ -f "$ROOT/.env" ]]; then set -a; source "$ROOT/.env"; set +a; fi

if command -v uv >/dev/null 2>&1; then
  args=(); for d in "${DEPS[@]}"; do args+=(--with "$d"); done
  exec uv run --no-project --python 3.13 "${args[@]}" python "$@"
fi

VENV="$ROOT/.local/venv"
if [[ ! -x "$VENV/bin/python" ]]; then
  mkdir -p "$ROOT/.local"
  python3 -m venv "$VENV"
  "$VENV/bin/pip" install -q "${DEPS[@]}"
fi
exec "$VENV/bin/python" "$@"
```

`scripts/bootstrap.py` creates the tables, TTL settings and buckets, and confirms the queues exist. It is idempotent.

**File:** `scripts/bootstrap.py`

```python
#!/usr/bin/env python3
"""
Create (idempotently) everything the services expect in the local infrastructure:
DynamoDB tables + TTL, S3 buckets. SQS queues are pre-created by deploy/compose/elasticmq.conf.

    scripts/py.sh scripts/bootstrap.py
"""
import os
import sys
import time

import boto3
from botocore.exceptions import ClientError, EndpointConnectionError

REGION = os.environ.get("AWS_REGION", "ap-south-1")
CREDS = dict(aws_access_key_id=os.environ.get("AWS_ACCESS_KEY_ID", "local"),
             aws_secret_access_key=os.environ.get("AWS_SECRET_ACCESS_KEY", "local"), region_name=REGION)
ddb = boto3.client("dynamodb", endpoint_url=os.environ.get("DYNAMO_ENDPOINT", "http://localhost:9700"), **CREDS)
s3 = boto3.client("s3", endpoint_url=os.environ.get("S3_ENDPOINT", "http://localhost:9703"), **CREDS)
sqs = boto3.client("sqs", endpoint_url=os.environ.get("SQS_ENDPOINT", "http://localhost:9702"), **CREDS)

PK_SK = [{"AttributeName": "PK", "AttributeType": "S"}, {"AttributeName": "SK", "AttributeType": "S"}]
KEYS = [{"AttributeName": "PK", "KeyType": "HASH"}, {"AttributeName": "SK", "KeyType": "RANGE"}]

# table name -> (extra attribute definitions, GSIs, TTL attribute or None)
TABLES = {
    "Users": ([], [], None),
    "Problems": (
        [{"AttributeName": "listPk", "AttributeType": "S"}, {"AttributeName": "number", "AttributeType": "S"}],
        [{"IndexName": "byNumber",
          "KeySchema": [{"AttributeName": "listPk", "KeyType": "HASH"}, {"AttributeName": "number", "KeyType": "RANGE"}],
          "Projection": {"ProjectionType": "ALL"}}],
        None),
    "Submissions": ([], [], "ttl"),
    "Contests": ([], [], None),
    "UserStats": ([], [], None),
    "UserSolved": ([], [], None),
    "AuditLog": ([], [], None),
    "IngestionRuns": ([], [], "ttl"),
}
BUCKETS = ["codearena-tests", "codearena-submissions"]
QUEUES = ["contest-submissions", "practice-submissions", "verdict-events", "contest-verdicts", "ingestion-jobs"]


def wait_for(name, fn, attempts=30):
    for i in range(attempts):
        try:
            return fn()
        except (EndpointConnectionError, ConnectionError, ClientError) as e:
            if isinstance(e, ClientError) and i > 3:
                raise
            print(f"  waiting for {name}... ({e.__class__.__name__})")
            time.sleep(2)
    sys.exit(f"{name} is not reachable; is `docker compose ... up` running?")


def create_tables():
    existing = set(wait_for("DynamoDB", lambda: ddb.list_tables()["TableNames"]))
    for name, (extra_attrs, gsis, ttl_attr) in TABLES.items():
        if name in existing:
            print(f"table {name}: exists")
            continue
        args = dict(TableName=name, BillingMode="PAY_PER_REQUEST",
                    AttributeDefinitions=PK_SK + extra_attrs, KeySchema=KEYS)
        if gsis:
            args["GlobalSecondaryIndexes"] = gsis
        ddb.create_table(**args)
        if ttl_attr:
            ddb.update_time_to_live(TableName=name,
                                    TimeToLiveSpecification={"Enabled": True, "AttributeName": ttl_attr})
        print(f"table {name}: created")


def create_buckets():
    try:
        have = {b["Name"] for b in wait_for("S3", lambda: s3.list_buckets().get("Buckets", []))}
    except ClientError as e:
        if e.response.get("Error", {}).get("Code") == "InternalError":
            sys.exit("S3Mock answered 500 InternalError: it cannot write its store. Run\n"
                     "  docker compose -f deploy/compose/docker-compose.yml logs s3 | tail -30\n"
                     "and see chapter 16 (S3Mock InternalError): recreate the s3 container and its volume.")
        raise
    for b in BUCKETS:
        if b not in have:
            s3.create_bucket(Bucket=b, CreateBucketConfiguration={"LocationConstraint": REGION})
        print(f"bucket {b}: ok")


def check_queues():
    for q in QUEUES:
        wait_for("SQS", lambda: sqs.get_queue_url(QueueName=q))
        print(f"queue {q}: ok")


if __name__ == "__main__":
    create_tables()
    create_buckets()
    check_queues()
    print("bootstrap complete")
```

`scripts/up.sh` is the one command to run after a reboot: start containers, wait for ports, create the JWT
signing key, bootstrap, seed. The seed script and problems arrive in chapter 06; until then `up.sh` prints
`skipped: scripts/seed.py and seed/problems arrive in chapter 06`, which is expected.

**File:** `scripts/up.sh`

```bash
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
```

`scripts/stop.sh` stops things at the level you want and reports leftover ports.

**File:** `scripts/stop.sh`

```bash
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
```

```bash
chmod +x scripts/*.sh scripts/*.py
```

## Check

```bash
scripts/up.sh
docker compose -f deploy/compose/docker-compose.yml ps        # 5 containers running
scripts/py.sh -c "import boto3; print(boto3.client('dynamodb', endpoint_url='http://localhost:9700',
  region_name='ap-south-1', aws_access_key_id='local', aws_secret_access_key='local').list_tables()['TableNames'])"
```

You should see the eight tables. Open http://localhost:9707 to see the queues. To browse DynamoDB and Redis in the
browser, run `TOOLS=1 scripts/up.sh` and open http://localhost:9705 and http://localhost:9706.

> These consoles have no login and full write access, which is acceptable only because every port is bound to
> 127.0.0.1. Never change the bindings to 0.0.0.0.
