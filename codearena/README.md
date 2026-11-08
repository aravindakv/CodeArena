# CodeArena

A LeetCode-style practice and coding-fest platform that runs entirely on your machine:
Spring Boot microservices behind a gateway, a React + Monaco web app, a gVisor-sandboxed judge,
a Redis leaderboard refreshed every 5 seconds, per-user stats, and an admin-only AI + RAG pipeline
that writes and verifies new problems.

## Quick start (Linux)

```bash
cp .env.example .env                    # adjust if you like
touch settings.gradle.kts && gradle wrapper --gradle-version 9.1.0    # once, only if ./gradlew is missing (Gradle 9.1, JDK 25)
scripts/up.sh                           # infrastructure in Docker + tables + seed problems
sandbox-images/build-all.sh             # code runner images (one per language)
scripts/services.sh start               # all Java services as local processes
cd frontend && npm install && npm run dev
```

Open http://localhost:5173. Register with the email in `BOOTSTRAP_ADMIN_EMAIL` to get the admin role.
Check the whole stack end to end with `scripts/smoke.sh`.

## Layout

| Path | What |
| --- | --- |
| `services/api-gateway` | Routing, JWT check, roles, Redis rate limits, circuit breakers (port 9080) |
| `services/auth-service` | Register, login, refresh rotation, JWKS, admin role management (9081) |
| `services/problem-service` | Problem catalog, 100-per-page list, starter code, admin review, ingestion runs (9082) |
| `services/submission-service` | Accepts code, queues it, reports verdicts (9083) |
| `services/judge-worker` | Runs code in the sandbox and judges it (no port) |
| `services/contest-service` | Fests, registration, Lua-scored leaderboard, 5 s snapshots (9084) |
| `services/analytics-service` | Per-user stats, streaks, heatmap, admin overview (9085) |
| `libs/common`, `libs/codegen` | Shared events/security/errors; starter code + harness generator |
| `sandbox-images` | One runner image per language |
| `frontend` | React + Vite + Tailwind + Monaco |
| `ingestion` | Python AI + RAG problem pipeline (admin only) |
| `deploy/compose` | Local infrastructure: DynamoDB Local, Redis, ElasticMQ (SQS), S3Mock, pgvector |
| `scripts` | up, services, stop, seed, smoke, ingest |
| `load` | k6 fest load test |

The step-by-step build guide (chapters 00–16) explains every file.