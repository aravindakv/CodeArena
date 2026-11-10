# 16 · Troubleshooting

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| `Configuring project ':services:...' without an existing directory is not allowed` | an older `settings.gradle.kts` lists modules you have not created yet | use the current `settings.gradle.kts` (chapter 01), which skips missing modules |
| `bootstrap.py`: `waiting for S3... (ClientError)`, then `InternalError ... ListBuckets` | S3Mock 5.2+ runs as a non-root user and cannot write its named volume (or the volume holds 4.x data, which 5.x cannot read) | use the current `docker-compose.yml` (pinned `5.2.3`, `user: root`, store at `/s3mockroot`), then `docker compose -f deploy/compose/docker-compose.yml rm -sf s3 && docker volume rm codearena_s3-data && scripts/up.sh` |
| `http://localhost:9707` gives `ERR_CONNECTION_RESET` | newer `elasticmq-native` images have no built-in web UI on 9325; the console is now the separate `softwaremill/elasticmq-ui` image | use the current `docker-compose.yml` (service `sqs-ui`, 9707 → 3000), then `scripts/up.sh`; queues themselves are unaffected |
| `:build-logic:generateExternalPluginSpecBuilders` fails: `Cannot resolve external dependency ... because no repositories are defined` | `build-logic/settings.gradle.kts` is missing or has no repositories (it must sit inside `build-logic/`, next to its `build.gradle.kts`) | use the current `build-logic/build.gradle.kts`, which declares the repositories itself, then rerun |
| `POST /api/v1/submissions` → `503 TRY_AGAIN` | the gateway's circuit breaker caught a failure calling submission-service: not running, still starting, an error inside it, or slower than the time limit | `grep fallback .local/logs/api-gateway.log` shows the cause; call the service directly on `:9083` with the same token to see its real error; check `.local/logs/submission-service.log` |
| `./gradlew` not found | the wrapper was not created | `gradle wrapper --gradle-version 9.1.0` from the repo root (it needs `settings.gradle.kts` to exist) |
| Build fails and "What went wrong" shows only `25.0.x` | an old Gradle (8.x) runs on JDK 25 | check `distributionUrl` in `gradle/wrapper/gradle-wrapper.properties` says 9.1.0, then `./gradlew --stop` |
| `Downloading ... gradle-9.1.0-bin.zip failed: timeout` while curl works | Java cannot reach the GitHub download host on your network | `curl -L -o ~/gradle-dists/gradle-9.1.0-bin.zip <url>`, set `distributionUrl=file\:...` in `gradle/wrapper/gradle-wrapper.properties` (do not commit it) |
| `Directory ... does not contain a Gradle build` | `gradle wrapper` run in a folder with no settings file | `touch settings.gradle.kts`, run it, delete the file, then extract (chapter 01) |
| Build fails resolving a Spring/AWS artifact | a version in `Versions.kt` does not exist yet or anymore | use the latest patch of the same line; keep Boot 4.0.x / Cloud 2025.1.x / Cloud AWS 4.0.x together |
| `package com.fasterxml.jackson.databind does not exist` in your own new code | Boot 4 uses Jackson 3 | import from `tools.jackson.databind`; annotations stay in `com.fasterxml.jackson.annotation` |
| Gateway starts but every route 404s | old property prefix `spring.cloud.gateway.routes` | use `spring.cloud.gateway.server.webflux.routes` |
| `Could not find org.testcontainers:junit-jupiter` | Testcontainers 2 renamed it | `org.testcontainers:testcontainers-junit-jupiter` |
| `npm run dev` fails with an engine or syntax error | Node older than 22.12 | `nvm install 24 && nvm use 24` |
| `Address already in use` | another stack on that port, or a stray process | `scripts/stop.sh services` prints ports still in use; `ss -ltnp \| grep 908` shows the owner |
| Every service logs `Unable to connect to Redis` | Compose not running, or Redis on a different port | `scripts/up.sh`; check `REDIS_PORT=9701` in `.env` |
| `ResourceNotFoundException: Cannot do operations on a non-existent table` | bootstrap not run (or DynamoDB volume wiped) | `scripts/up.sh` |
| `QueueDoesNotExist` | ElasticMQ started without its config | `docker compose -f deploy/compose/docker-compose.yml up -d --force-recreate sqs` |
| S3 calls fail with a DNS error like `codearena-tests.localhost` | virtual-host addressing | `spring.cloud.aws.s3.path-style-access-enabled: true` (in `codearena-common.yml`) |
| Gateway answers 404 for every `/api/v1/...` | routes not loaded (an old property prefix) | `GET /actuator/gateway/routes` with a token; routes live under `spring.cloud.gateway.server.webflux.routes` |
| 401 everywhere after restarting auth-service | the signing key changed | `.local/jwt-private.pem` must exist before auth starts (`scripts/up.sh` creates it); log in again |
| 401 `invalid_token` mentioning the audience | token issued for another audience | all services share `audiences: codearena-api` from `codearena-common.yml` |
| Logged in, but the Admin tab is missing | the account is not ADMIN | register with `BOOTSTRAP_ADMIN_EMAIL`, or have an admin grant it under Admin → Users (applies at the next token refresh) |
| 429 on login while testing | the per-IP limit (burst 10, then 1/s) | wait ten seconds, or raise `redis-rate-limiter` values in the gateway's `application.yml` locally |
| Submission stays PENDING | judge-worker not running, or it cannot start containers | `scripts/services.sh logs judge-worker`; `docker info \| grep -i runtimes` should list `runsc` |
| Every submission is SYSTEM_ERROR | runner image missing or `docker run` failing | `sandbox-images/build-all.sh`; the judge log shows the `docker run` error |
| `unknown or invalid runtime name: runsc` | gVisor not registered with Docker | `sudo runsc install && sudo systemctl reload docker`, or `JUDGE_RUNTIME=runc` for dev |
| Java submissions are slow or MLE | JVM start-up and heap inside 256 MB | expected; the judge adds 800 ms and 320 MB for Java. Raise a problem's limits if needed |
| C++ compile killed | g++ needs memory for `bits/stdc++` + the JSON header | the judge gives C++ 512 MB extra; on a small machine lower `JUDGE_CONCURRENCY` |
| Leaderboard empty during a live fest | the user did not register, or verdicts are compile errors (no penalty, not shown) | register first; check `contest-verdicts` in the SQS console |
| Leaderboard stopped updating | the board is frozen, or contest-service is down | Admin → Fests shows "frozen"; `scripts/services.sh status` |
| Problem list does not show a newly published problem | list cache | publishing bumps the cache version; if you edited DynamoDB by hand, run `redis-cli -p 9701 INCR problems:list:version` |
| Ingestion seeds stay QUEUED | the worker is not running | `scripts/ingest.sh` |
| Ingestion seeds FAILED with "the reference solution failed" | runner image or problem-service `/internal/codegen` not reachable | build the python runner; `curl localhost:9082/actuator/health` |
| `pip`/`uv` cannot build `psycopg` | old pip | `uv` is recommended; `psycopg[binary]` ships wheels |
| Monaco shows a blank editor | the worker files failed to load | hard refresh; `npm run build` must not report errors |

## Useful one-liners

```bash
scripts/services.sh logs <service>                                   # follow a log
docker compose -f deploy/compose/docker-compose.yml logs -f sqs      # infrastructure logs
redis-cli -p 9701 KEYS 'lb:*'                                        # leaderboard keys (dev only; never KEYS in prod)
docker ps --filter name=ca-                                          # sandboxes running right now
```
