# CodeArena architecture

CodeArena is a coding practice and fest platform built as a set of Spring Boot services behind one gateway.
User code never runs inside a service: it is queued and executed by a separate judge in throw-away, gVisor-isolated
containers. This document describes the local edition (everything on one Linux machine) and how it maps to AWS.

Diagrams are Mermaid; they render on GitHub, GitLab and in VS Code (Markdown Preview Mermaid Support extension).

## 0. Block diagram (one-page overview)

![CodeArena system block diagram](architecture-block.png)

The picture is a screenshot of [`architecture-block.html`](architecture-block.html), a self-contained HTML page:
open it in any browser for a sharp, zoomable version, or edit it and re-capture the PNG. Read it top to bottom:
requests go clients → gateway → services → queues → execution, and verdicts come back up the queues. Every layer
reads and writes the data layer at the bottom; only the execution plane ever runs user or LLM-written code.

## 0b. Data flow, end to end

![CodeArena end-to-end data flow](data-flow.png)

Source: [`data-flow.html`](data-flow.html). It follows the data through seven stages (create a problem, sign in,
browse, submit, judge, fan out, see results), labels every arrow with the data it carries, and marks which data
store (D1 to D10) each step reads or writes, with a data dictionary at the bottom.

## 1. Context: who uses it and what it talks to

```mermaid
flowchart LR
  user([USER<br/>solves problems, joins fests])
  admin([ADMIN<br/>curates problems, runs fests])
  mobile([Mobile apps<br/>same /api/v1])
  ca[[CodeArena platform]]
  llm[(LLM API<br/>Claude, or offline mock)]

  user -- browser --> ca
  mobile -- HTTPS + JWT --> ca
  admin -- browser, admin console --> ca
  ca -- AI ingestion only --> llm
```

| Role | Can do |
| --- | --- |
| USER | browse problems, run and submit code in Java, Python, C++, JavaScript or Go, register for fests, see own progress |
| ADMIN | everything a user can, plus author, review, publish and retire problems, start AI + RAG ingestion runs, create and freeze fests, view platform analytics, grant admin |

## 2. Components

```mermaid
flowchart TB
  subgraph clients[Clients]
    web[Web app<br/>React 19 + Monaco<br/>:5173]
    app[Android / iOS]
  end

  gw[api-gateway :9080<br/>routing, JWT, roles,<br/>rate limits, circuit breakers]

  subgraph svc[Spring Boot 4 services]
    auth[auth-service :9081<br/>register, login,<br/>refresh, JWKS, roles]
    prob[problem-service :9082<br/>catalog, starter code,<br/>review, ingestion runs]
    sub[submission-service :9083<br/>accept, store, queue]
    con[contest-service :9084<br/>fests, Lua scoring,<br/>5 s snapshots]
    ana[analytics-service :9085<br/>stats, streaks, heatmap]
  end

  subgraph exec[Execution, no HTTP port]
    judge[judge-worker<br/>runs code, decides verdicts]
    ing[ingestion worker, Python<br/>AI + RAG, admin-triggered]
  end

  subgraph queues[Queues: ElasticMQ :9702, SQS on AWS]
    qc[[contest-submissions]]
    qp[[practice-submissions]]
    qv[[verdict-events]]
    qcv[[contest-verdicts]]
    qi[[ingestion-jobs]]
  end

  subgraph data[Data]
    ddb[(DynamoDB Local :9700)]
    redis[(Redis :9701)]
    s3[(S3Mock :9703<br/>code, hidden tests)]
    pg[(pgvector :9704<br/>problem embeddings)]
  end

  sandbox{{gVisor sandboxes<br/>one container per run}}

  web -- /api --> gw
  app --> gw
  gw --> auth & prob & sub & con & ana
  gw -. rate limits, logout deny list .- redis

  sub --> qc & qp
  qc & qp --> judge
  judge --> sandbox
  judge --> qv & qcv
  qv --> ana
  qcv --> con
  prob --> qi --> ing

  judge -. judge metadata /internal .-> prob
  ing -. codegen /internal .-> prob
  ing --> sandbox
  ing --> pg

  auth & prob & sub & con & ana & judge & ing --- ddb
  prob & sub & con & ana --- redis
  prob & sub & judge & ing --- s3
```

| Component | Owns | Key idea |
| --- | --- | --- |
| api-gateway | routes, edge security | the only entry point; services also re-check roles |
| auth-service | users, roles, tokens | the only holder of the signing key; everyone else verifies with JWKS |
| problem-service | problems, hidden-test versions, ingestion runs | one language-neutral signature generates starter code and harnesses for 5 languages |
| submission-service | submissions | never runs code; returns 202 in milliseconds |
| judge-worker | verdicts | stateless, horizontally scalable, idempotent |
| contest-service | fests, leaderboards | one precomputed snapshot every 5 s, whatever the number of viewers |
| analytics-service | per-user stats | pre-aggregated counters, ready at next login |
| ingestion worker | generated problems (in review) | LLM output is verified by executing it, never trusted |

## 3. Request path through the gateway

```mermaid
flowchart LR
  req([HTTPS request<br/>/api/v1/...]) --> size{Body larger<br/>than 128 KB?}
  size -- yes --> r413[413]
  size -- no --> jwt{JWT valid?<br/>signature, iss, aud, exp}
  jwt -- invalid --> r401[401]
  jwt -- valid or<br/>public route --> role{Role allowed?<br/>/admin needs ADMIN}
  role -- no --> r403[403]
  role -- yes --> deny{jti on logout<br/>deny list?}
  deny -- yes --> r401b[401]
  deny -- no --> rl{Within rate limit?<br/>per user or IP}
  rl -- no --> r429[429 + Retry-After]
  rl -- yes --> hdr[strip forged X-User-*<br/>add X-User-Id, X-Request-Id]
  hdr --> cb{Circuit breaker<br/>closed?}
  cb -- open or timeout --> r503[503 fallback<br/>Retry-After: 5]
  cb -- closed --> svc[service: verifies JWT again,<br/>checks roles again]
```

| Route | Limit |
| --- | --- |
| `POST /auth/login`, `/auth/register` | 1/s per IP, burst 10 |
| `POST /submissions` | 1/s per user, burst 5 |
| everything else | no gateway limit (services are cached and cheap) |

## 4. Authentication and token refresh

```mermaid
sequenceDiagram
  autonumber
  participant B as Browser or app
  participant G as api-gateway
  participant A as auth-service
  participant R as Redis
  participant D as DynamoDB (Users)

  B->>G: POST /api/v1/auth/login {email, password}
  G->>A: /v1/auth/login
  A->>D: get EMAIL#email, then USER#id
  A->>A: BCrypt check, sign RS256 JWT (15 min, 5 min for admins)
  A->>R: rt:sha256(refresh) = userId|family (TTL 7 days)
  A-->>B: accessToken in body, refresh token in HttpOnly cookie (body too, for mobile)

  Note over B,G: every API call: Authorization: Bearer accessToken
  B->>G: GET /api/v1/me/stats
  G->>G: verify signature with cached JWKS
  G->>A: (only when keys rotate) GET /.well-known/jwks.json

  Note over B,A: access token expired
  B->>G: POST /api/v1/auth/refresh (cookie or body)
  G->>A: /v1/auth/refresh
  A->>R: GETDEL rt:hash (single use)
  alt token already used
    A->>R: revoke whole family
    A-->>B: 401, log in again
  else valid
    A->>R: store new refresh token
    A-->>B: new access + refresh token
  end
```

## 5. Submitting code: from click to verdict

```mermaid
sequenceDiagram
  autonumber
  participant U as Browser
  participant G as api-gateway
  participant S as submission-service
  participant R as Redis
  participant O as S3
  participant D as DynamoDB
  participant Q as SQS
  participant J as judge-worker
  participant X as gVisor sandbox
  participant C as contest-service
  participant N as analytics-service

  U->>G: POST /submissions {slug, lang, code, mode, contestId?}<br/>Idempotency-Key
  G->>S: rate limit passed
  S->>R: judge metadata (cached 5 min), contest window and registration
  S->>O: put src/<id>
  S->>D: Submissions item status=PENDING
  S->>Q: SubmissionCreated (contest or practice queue)
  S-->>U: 202 {submissionId}

  loop every 0.4 to 2.5 s
    U->>G: GET /submissions/{id}
    G->>S: read item
    S-->>U: status PENDING
  end

  Q->>J: SubmissionCreated
  J->>O: source + hidden tests (cached on disk by version)
  J->>X: docker run --runtime=runsc, no network, limits
  X-->>J: results.jsonl, exit code, time, peak memory
  J->>J: compare outputs, decide verdict
  J->>D: write verdict only if still PENDING
  J->>Q: VerdictReady to verdict-events and contest-verdicts
  Q->>C: contest verdict, apply score.lua
  Q->>N: verdict, ADD counters
  U->>G: GET /submissions/{id}
  G->>S: read item
  S-->>U: ACCEPTED, 17/17 tests, 42 ms
```

Two queues keep fests fast: contest submissions never wait behind practice traffic.
**Run** mode judges only the visible examples (plus an optional custom input), is not counted, and expires after a day.

## 6. Inside the sandbox

```mermaid
flowchart TB
  subgraph host[Judge host: runs nothing else, recycled often on AWS]
    jw[judge-worker JVM]
    subgraph ctr[Container per run: gVisor runsc]
      direction TB
      work[/work read-only<br/>user code + harness + test INPUTS/]
      prog[program, uid 65534<br/>no capabilities]
      out[/out writable<br/>results.jsonl, stdout, meta/]
      tmp[/tmp tmpfs 64-512 MB/]
      work --> prog --> out
      prog --- tmp
    end
    exp[(expected outputs<br/>stay on the host)]
    jw -- mounts --> ctr
    jw -- compares --> exp
  end
```

| Threat | Control | Verdict |
| --- | --- | --- |
| infinite loop | `timeout -s KILL` inside, wall-clock kill outside | TIME_LIMIT_EXCEEDED |
| memory bomb | `--memory` = `--memory-swap` | MEMORY_LIMIT_EXCEEDED |
| fork bomb | `--pids-limit=128` | RUNTIME_ERROR |
| network, cloud metadata | `--network=none` | RUNTIME_ERROR |
| writing files, filling disk | `--read-only`, small tmpfs, `fsize` ulimit | RUNTIME_ERROR |
| output flood | 64 KB cap | OUTPUT_LIMIT_EXCEEDED |
| reading the answers | expected outputs never enter the container | WRONG_ANSWER |
| kernel exploit, escape | gVisor user-space kernel, `no-new-privileges`, non-root | contained |
| judge crash, poison message | SQS redelivery, conditional verdict write, SYSTEM_ERROR after 3 attempts, DLQ | queue keeps flowing |

## 7. Live fest leaderboard

```mermaid
sequenceDiagram
  autonumber
  participant J as judge-worker
  participant Q as contest-verdicts queue
  participant C as contest-service pods
  participant R as Redis
  participant V as 100,000 viewers

  J->>Q: VerdictReady (contestId set)
  Q->>C: one pod consumes it
  C->>R: EVALSHA score.lua<br/>dedupe by submission, count wrong attempts,<br/>ZINCRBY lb:{cid} solved*1e6 - penalty

  loop every 5 seconds
    C->>R: SET lb:lock:{cid} NX EX 4 (one pod wins)
    C->>R: ZREVRANGE lb:{cid} 0 99 + names + per-problem cells
    C->>R: SET lb:snap:{cid} JSON EX 10
  end

  loop every 5 seconds, each viewer
    V->>C: GET /contests/{cid}/leaderboard
    C->>R: GET lb:snap:{cid}
    C-->>V: snapshot, Cache-Control max-age=5
  end
```

Score = solved × 1,000,000 − penalty minutes (ICPC: minutes to accept + 20 per wrong attempt before it).
All keys of one contest share the `{cid}` hash tag, so they sit on one Redis Cluster slot; TTLs remove them a week
after the fest. Reading the board costs one Redis GET, so it scales with a CDN, not with the database.

## 8. Admin-only AI + RAG ingestion

```mermaid
flowchart LR
  admin([ADMIN: topics,<br/>difficulties, count]) --> run[problem-service<br/>creates run + seeds]
  run --> q[[ingestion-jobs]] --> w[ingestion worker]

  w --> retrieve[Retrieve: 3 nearest<br/>problems from pgvector]
  retrieve --> gen[Generate with LLM<br/>JSON schema: statement, examples,<br/>signature, reference, brute force, generator]
  gen --> dedupe{Similarity ≥ 0.92<br/>to an existing problem?}
  dedupe -- yes --> dup[DUPLICATE]
  dedupe -- no --> tests[Run generator in sandbox:<br/>38 test inputs]
  tests --> outputs[Run reference in sandbox:<br/>expected outputs]
  outputs --> ex{Statement examples<br/>match the reference?}
  ex -- no --> retry[feed the error back,<br/>retry once]
  ex -- yes --> brute{Brute force agrees<br/>on small inputs?}
  brute -- no --> retry
  brute -- yes --> langs[Optional: translate and run<br/>in Java, C++, JS, Go]
  langs --> review[IN_REVIEW + tests v1<br/>+ embedding stored]
  retry --> gen
  review --> decision{Admin reviews}
  decision -- publish --> pub[PUBLISHED, numbered,<br/>visible to users]
  decision -- reject --> rej[REJECTED]
```

The model writes inputs, never expected outputs; outputs come from executing the reference solution. All
LLM-written code runs in the same sandbox as user code. Problems are original; nothing is scraped.

## 9. Problem lifecycle

```mermaid
stateDiagram-v2
  [*] --> DRAFT: admin creates manually
  [*] --> IN_REVIEW: AI ingestion
  DRAFT --> PUBLISHED: publish (needs hidden tests)
  IN_REVIEW --> PUBLISHED: publish
  IN_REVIEW --> REJECTED: reject
  PUBLISHED --> RETIRED: retire
  PUBLISHED --> PUBLISHED: upload tests vN+1 / edit
```

Publishing takes the next problem number from an atomic counter and bumps the list cache version, so every
user sees the new list at once.

## 10. Data ownership

```mermaid
erDiagram
  USERS ||--o{ SUBMISSIONS : makes
  USERS ||--|| USER_STATS : has
  USERS ||--o{ USER_SOLVED : has
  PROBLEMS ||--o{ SUBMISSIONS : for
  PROBLEMS ||--o{ USER_SOLVED : of
  CONTESTS ||--o{ SUBMISSIONS : during
  CONTESTS ||--o{ CONTEST_REG : has
  INGESTION_RUNS ||--o{ PROBLEMS : produces

  USERS {
    string partitionKey PK "USER#id or EMAIL#email"
    string roles "USER, ADMIN"
  }
  PROBLEMS {
    string partitionKey PK "PROBLEM#slug"
    string status
    number num "GSI byNumber"
    int testsVersion
  }
  SUBMISSIONS {
    string partitionKey PK "USER#id"
    string sortKey "SUB#ulid"
    string status
    number ttl "RUN mode"
  }
  CONTESTS {
    string partitionKey PK "CONTEST#id"
    string sortKey "META, REG#user, RANK#n"
  }
  CONTEST_REG {
    string sortKey "REG#userId"
  }
  USER_STATS {
    string partitionKey PK "USER#id"
    string sortKey "STATS, DAY#date, PREFS"
  }
  USER_SOLVED {
    string partitionKey PK "USER#id"
    string sortKey "PROBLEM#slug"
  }
  INGESTION_RUNS {
    string partitionKey PK "RUN#id"
    string sortKey "META, SEED#nnn"
  }
```

| Store | Holds | Owner |
| --- | --- | --- |
| DynamoDB `Users`, `AuditLog` | accounts, roles, admin actions | auth-service (+ problem-service audit) |
| DynamoDB `Problems` | problem docs, counters, acceptance | problem-service (analytics adds counters) |
| DynamoDB `Submissions` | every run and verdict | submission-service, judge-worker |
| DynamoDB `Contests` | fests, registrations, final standings | contest-service |
| DynamoDB `UserStats`, `UserSolved` | progress, heatmap, preferences | analytics-service |
| DynamoDB `IngestionRuns` | AI run progress | problem-service, ingestion worker |
| S3 `codearena-tests` | `<slug>/v<n>/cases.jsonl`, immutable | problem-service, ingestion |
| S3 `codearena-submissions` | `src/<submissionId>` | submission-service |
| Redis | rate limits, refresh tokens, deny list, caches, leaderboards, contest gate | gateway, auth, problem, submission, contest, analytics |
| pgvector | problem embeddings for RAG and dedupe | ingestion worker |

## 11. Local deployment (this guide)

```mermaid
flowchart TB
  subgraph laptop[Your Linux machine, all ports on 127.0.0.1]
    subgraph procs[Processes: scripts/services.sh, JDK 25]
      p1[api-gateway :9080]
      p2[auth :9081]
      p3[problem :9082]
      p4[submission :9083]
      p5[contest :9084]
      p6[analytics :9085]
      p7[judge-worker]
    end
    vite[Vite dev server :5173<br/>proxies /api to :9080]
    py[ingestion worker<br/>scripts/ingest.sh]
    subgraph docker[Docker Compose: scripts/up.sh]
      d1[(DynamoDB Local :9700)]
      d2[(Redis :9701)]
      d3[[ElasticMQ :9702 / console :9707]]
      d4[(S3Mock :9703)]
      d5[(pgvector :9704)]
    end
    sb{{runner containers<br/>codearena/runner-*:1, gVisor}}
  end
  vite --> p1
  p7 --> sb
  py --> sb
  procs --> docker
  py --> docker
```

## 12. On AWS (after local testing)

```mermaid
flowchart TB
  dns[Route 53 latency routing] --> cf[CloudFront + WAF<br/>web app from S3, API caching]
  cf --> alb[ALB per region]
  subgraph region[Each region: ap-south-1, us-east-1, eu-west-1]
    alb --> eks[EKS: gateway + 5 services<br/>HPA, Karpenter]
    eks --> sqs[[SQS + DLQs]]
    sqs --> asg[Judge Auto Scaling Group<br/>EC2 Spot, gVisor,<br/>isolated subnet, no NAT]
    eb[EventBridge] --> eks
    asg --> eb
    eks --> cache[(ElastiCache Redis)]
  end
  eks <--> gt[(DynamoDB Global Tables<br/>replicated across regions)]
  asg --> s3r[(S3 + cross-region replication)]
  cache <-. Global Datastore .-> cache
```

| Local | AWS |
| --- | --- |
| DynamoDB Local | DynamoDB Global Tables (active-active, multi-region) |
| Redis container | ElastiCache (cluster mode) + Global Datastore |
| ElasticMQ, two verdict queues | SQS + EventBridge rules |
| S3Mock | S3 + cross-region replication |
| pgvector container | RDS PostgreSQL with pgvector |
| `java -jar` processes | EKS Deployments behind an ALB |
| judge on your machine | dedicated EC2 Auto Scaling Group on Spot, scaled on queue depth |
| `.local/jwt-private.pem` | Secrets Manager |

Only endpoints and credentials change between the two: the services use the standard AWS SDK throughout.

## 13. Capacity design

| Load | Estimate | Answer in the design |
| --- | --- | --- |
| 1 million users, ~20K concurrent normally | mostly reads | problem list served from one Redis entry; stateless JWT |
| 100K fest users submitting every ~2 min | ~830 submissions/s, bursts ~2,500/s | async queue; contest queue separate; judge fleet scales on queue depth |
| judge CPU at peak | ~415 busy CPUs | Spot instances, pre-warmed before the fest |
| leaderboard polling | 100K / 5 s = 20,000 req/s | one snapshot per 5 s, cacheable; one Redis GET per read |
| login burst at fest start | ~330/s | per-IP rate limit, BCrypt on auth pods only |
