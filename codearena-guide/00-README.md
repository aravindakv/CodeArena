# CodeArena: build a LeetCode-style platform, step by step (local edition)

This guide builds the whole platform on your own Linux machine: no cloud account, no costs. Every chapter adds one
working piece and ends with a check you can run. The AWS deployment comes later, once everything here is tested.

## How to use this guide

Each chapter contains complete files. A file block is always introduced by a line like **File:** `path/to/File.java`.
You can type them in yourself (recommended for the parts you want to learn deeply) or let `extract_code.py` write them:

```bash
mkdir -p ~/Projects/codearena && cd ~/Projects/codearena
python3 /path/to/extract_code.py /path/to/codearena-guide/01-*.md            # dry run: lists the files
python3 /path/to/extract_code.py /path/to/codearena-guide/01-*.md --write    # writes them
python3 /path/to/extract_code.py /path/to/codearena-guide/*.md --write       # every chapter at once
```

Existing files are never overwritten unless you add `--force`. Code shown without a **File:** line is an
example or a command, never a file to create. The complete repository is also in `codearena-repo.zip`.

## What you are building

```mermaid
flowchart LR
  B[Browser / mobile app] -->|/api/v1| G[api-gateway :9080<br/>JWT, roles, rate limits,<br/>circuit breakers]
  G --> A[auth-service :9081]
  G --> P[problem-service :9082]
  G --> S[submission-service :9083]
  G --> C[contest-service :9084]
  G --> N[analytics-service :9085]
  S -->|SubmissionCreated| Q[(SQS: contest / practice queues)]
  Q --> J[judge-worker<br/>Docker + gVisor sandbox]
  J -->|VerdictReady| E[(verdict-events,<br/>contest-verdicts)]
  E --> C
  E --> N
  P -->|ingestion-jobs| I[ingestion worker<br/>AI + RAG, admin only]
  A & P & S & C & N & J & I --- D[(DynamoDB Local)]
  G & C & S & P --- R[(Redis)]
  P & S & J & I --- O[(S3Mock)]
  I --- V[(pgvector)]
```

The full architecture, with 12 diagrams (components, gateway path, auth, submit-to-verdict, sandbox, leaderboard,
AI ingestion, data model, local and AWS deployment), is in its own file, `architecture.md`, shipped next to this
guide. Keep a copy in the repo so it travels with the code:

**File:** `docs/architecture.md`

````markdown
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
````

`architecture.md` opens with a one-page block diagram. Its source is an HTML page; save it as
`docs/architecture-block.html`, open it in a browser, and save a screenshot as `docs/architecture-block.png`
(the PNG also ships ready-made in the repo zip and next to this guide):

**File:** `docs/architecture-block.html`

```html
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>CodeArena Block Diagram</title>
<!-- Optional web fonts; the diagram falls back to system fonts offline. -->
<link rel="preconnect" href="https://fonts.googleapis.com">
<link href="https://fonts.googleapis.com/css2?family=Bricolage+Grotesque:wght@600;700&family=IBM+Plex+Sans:wght@400;500;600&family=JetBrains+Mono:wght@400;500&display=swap" rel="stylesheet">
<style>
  :root {
    --bg: #0d1023;
    --panel: #141833;
    --line: #2a3060;
    --text: #e7e9f7;
    --muted: #9aa0c8;
    --amber: #ffb547;

    --client: #5b8cff;
    --edge: #a77bff;
    --service: #3fc4a8;
    --queue: #f0c35a;
    --exec: #ff7a59;
    --data: #4fb3ff;
    --ext: #c2c6e0;

    --display: "Bricolage Grotesque", "Segoe UI", system-ui, sans-serif;
    --body: "IBM Plex Sans", "Segoe UI", system-ui, sans-serif;
    --mono: "JetBrains Mono", ui-monospace, "Cascadia Mono", Consolas, monospace;
  }
  * { box-sizing: border-box; }
  body {
    margin: 0;
    background: var(--bg);
    color: var(--text);
    font: 14px/1.45 var(--body);
  }
  .canvas {
    width: 1440px;
    margin: 0 auto;
    padding: 36px 40px 40px;
  }
  header {
    display: flex;
    align-items: flex-end;
    justify-content: space-between;
    gap: 24px;
    margin-bottom: 26px;
  }
  h1 {
    font: 700 30px/1.1 var(--display);
    margin: 0 0 6px;
    letter-spacing: -0.01em;
  }
  h1 span { color: var(--amber); }
  .sub { color: var(--muted); margin: 0; max-width: 760px; }

  .legend { display: flex; flex-wrap: wrap; gap: 8px 14px; justify-content: flex-end; max-width: 520px; }
  .legend i {
    font-style: normal; font-size: 12px; color: var(--muted);
    display: inline-flex; align-items: center; gap: 6px;
  }
  .legend i::before {
    content: ""; width: 12px; height: 12px; border-radius: 3px;
    background: var(--c); box-shadow: 0 0 0 1px rgba(255,255,255,.15) inset;
  }

  .layout { display: grid; grid-template-columns: 1fr 272px; gap: 22px; }

  /* Layers */
  .layer {
    display: grid;
    grid-template-columns: 132px 1fr;
    gap: 16px;
    align-items: stretch;
  }
  .layer-name {
    border-left: 3px solid var(--c);
    padding: 10px 0 10px 12px;
    font: 600 13px/1.25 var(--display);
    text-transform: uppercase;
    letter-spacing: .06em;
    color: var(--text);
  }
  .layer-name small {
    display: block; margin-top: 4px;
    font: 400 11.5px/1.35 var(--body);
    text-transform: none; letter-spacing: 0; color: var(--muted);
  }
  .row { display: grid; gap: 12px; }
  .cols-2 { grid-template-columns: 2fr 1fr; }
  .cols-3 { grid-template-columns: repeat(3, 1fr); }
  .cols-4 { grid-template-columns: repeat(4, 1fr); }
  .cols-5 { grid-template-columns: repeat(5, 1fr); }
  .exec-row { grid-template-columns: 1.15fr 1.6fr 1.15fr .8fr; }

  .block {
    position: relative;
    background: var(--panel);
    border: 1px solid var(--line);
    border-top: 3px solid var(--c);
    border-radius: 10px;
    padding: 12px 14px 12px;
  }
  .block h3 {
    margin: 0;
    font: 600 15px/1.2 var(--display);
    display: flex; justify-content: space-between; align-items: baseline; gap: 8px;
  }
  .port {
    font: 500 11.5px var(--mono);
    color: var(--c);
    white-space: nowrap;
  }
  .block p { margin: 4px 0 0; color: var(--muted); font-size: 12.5px; }
  .block ul { margin: 8px 0 0; padding: 0; list-style: none; }
  .block li {
    font-size: 12.5px; padding-left: 12px; position: relative; margin-top: 3px;
  }
  .block li::before {
    content: ""; position: absolute; left: 0; top: .62em;
    width: 5px; height: 5px; border-radius: 50%; background: var(--c);
  }

  .chips { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
  .chip {
    font: 500 11.5px var(--mono);
    padding: 4px 8px;
    border-radius: 6px;
    border: 1px solid color-mix(in srgb, var(--c) 45%, transparent);
    background: color-mix(in srgb, var(--c) 12%, transparent);
    color: var(--text);
  }

  /* Gateway is one wide block */
  .gateway { display: grid; grid-template-columns: 260px 1fr; gap: 16px; align-items: center; }

  /* Execution plane: hatched, to read as "isolated" */
  .isolated {
    border-style: dashed;
    border-top-style: solid;
    background:
      repeating-linear-gradient(135deg, rgba(255,122,89,.06) 0 8px, transparent 8px 16px),
      var(--panel);
  }
  .runners { display: grid; grid-template-columns: repeat(5, 1fr); gap: 6px; margin-top: 10px; }
  .runner {
    text-align: center; font: 500 11px var(--mono);
    padding: 6px 2px; border-radius: 6px;
    background: rgba(255,122,89,.12); border: 1px solid rgba(255,122,89,.4);
  }
  .external { border-style: dashed; }

  /* Connectors between layers */
  .link {
    display: grid; grid-template-columns: 132px 1fr; gap: 16px;
    height: 34px; align-items: center;
  }
  .link .wire {
    position: relative; height: 100%;
    display: flex; align-items: center; justify-content: center; gap: 28px;
  }
  .link .wire::before {
    content: ""; position: absolute; left: 50%; top: 0; bottom: 0;
    border-left: 2px dashed var(--line);
  }
  .tag {
    position: relative; z-index: 1;
    font: 500 11.5px var(--mono);
    color: var(--amber);
    background: var(--bg);
    border: 1px solid var(--line);
    border-radius: 999px;
    padding: 3px 10px;
  }
  .tag::after { content: " ↓"; color: var(--muted); }
  .tag.up::after { content: " ↑"; }

  /* Sidebar */
  aside { display: flex; flex-direction: column; gap: 14px; }
  .card {
    background: var(--panel); border: 1px solid var(--line); border-radius: 10px;
    padding: 14px 16px;
  }
  .card h4 {
    margin: 0 0 8px; font: 600 13px var(--display);
    text-transform: uppercase; letter-spacing: .06em; color: var(--amber);
  }
  .card ul { margin: 0; padding-left: 16px; }
  .card li { font-size: 12.5px; margin: 4px 0; color: var(--text); }
  .card li b { font-weight: 600; }
  .card .num { font: 700 22px/1 var(--display); color: var(--text); }
  .stat { display: flex; justify-content: space-between; align-items: baseline; padding: 6px 0; border-bottom: 1px solid var(--line); }
  .stat:last-child { border-bottom: 0; }
  .stat span { color: var(--muted); font-size: 12px; }

  footer {
    margin-top: 22px;
    display: grid; grid-template-columns: 1fr 1fr; gap: 14px;
  }
  footer .card h4 { color: var(--text); }
  footer .map { display: flex; flex-wrap: wrap; gap: 6px; }
  footer .map .chip { --c: var(--muted); }
  footer .aws .map .chip { --c: var(--amber); }
</style>
</head>
<body>
<div class="canvas">

  <header>
    <div>
      <h1>CodeArena <span>·</span> system block diagram</h1>
      <p class="sub">Layered view of the platform. Requests flow top to bottom; verdicts and events flow back up through
        queues. User code only ever runs in the isolated execution plane.</p>
    </div>
    <div class="legend">
      <i style="--c:var(--client)">Client</i>
      <i style="--c:var(--edge)">Edge</i>
      <i style="--c:var(--service)">Service</i>
      <i style="--c:var(--queue)">Queue</i>
      <i style="--c:var(--exec)">Isolated execution</i>
      <i style="--c:var(--data)">Data store</i>
      <i style="--c:var(--ext)">External</i>
    </div>
  </header>

  <div class="layout">
    <main>

      <!-- 1. Clients -->
      <section class="layer">
        <div class="layer-name" style="--c:var(--client)">Clients<small>one API for web and mobile</small></div>
        <div class="row cols-3">
          <div class="block" style="--c:var(--client)">
            <h3>Web app <span class="port">:5173</span></h3>
            <p>React 19 · Vite 8 · Tailwind 4 · TanStack Query</p>
            <ul><li>Problem list, 100 per page</li><li>Monaco editor, 5 languages</li><li>Live fest leaderboard</li></ul>
          </div>
          <div class="block" style="--c:var(--client)">
            <h3>Admin console <span class="port">ADMIN</span></h3>
            <p>Same web app, role-gated routes</p>
            <ul><li>Start AI + RAG ingestion</li><li>Review, publish, retire</li><li>Create and freeze fests</li></ul>
          </div>
          <div class="block" style="--c:var(--client)">
            <h3>Mobile apps <span class="port">/api/v1</span></h3>
            <p>Android / iOS, same versioned REST API</p>
            <ul><li>Refresh token in body</li><li>Cursor pagination</li><li>Idempotency-Key on submit</li></ul>
          </div>
        </div>
      </section>

      <div class="link"><div></div><div class="wire"><span class="tag">HTTPS · /api/v1 · Bearer JWT</span></div></div>

      <!-- 2. Edge -->
      <section class="layer">
        <div class="layer-name" style="--c:var(--edge)">Edge<small>single entry point</small></div>
        <div class="block gateway" style="--c:var(--edge)">
          <div>
            <h3>api-gateway <span class="port">:9080</span></h3>
            <p>Spring Cloud Gateway 5 (WebFlux)</p>
          </div>
          <div class="chips" style="margin-top:0">
            <span class="chip">routing</span>
            <span class="chip">JWT verify · JWKS</span>
            <span class="chip">role check /admin</span>
            <span class="chip">logout deny list</span>
            <span class="chip">rate limiter · Redis</span>
            <span class="chip">circuit breaker → 503</span>
            <span class="chip">128 KB body cap</span>
            <span class="chip">strip X-User-*</span>
            <span class="chip">X-Request-Id</span>
            <span class="chip">CORS</span>
          </div>
        </div>
      </section>

      <div class="link"><div></div><div class="wire"><span class="tag">REST · X-User-Id · JWT re-verified</span></div></div>

      <!-- 3. Services -->
      <section class="layer">
        <div class="layer-name" style="--c:var(--service)">Services<small>Spring Boot 4 · Java 25 · stateless</small></div>
        <div class="row cols-5">
          <div class="block" style="--c:var(--service)">
            <h3>auth <span class="port">:9081</span></h3>
            <ul><li>Register, login</li><li>RS256 JWT, 15 min</li><li>Refresh rotation</li><li>JWKS, roles</li></ul>
          </div>
          <div class="block" style="--c:var(--service)">
            <h3>problem <span class="port">:9082</span></h3>
            <ul><li>Catalog + cache</li><li>Starter code ×5</li><li>Hidden test versions</li><li>Ingestion runs</li></ul>
          </div>
          <div class="block" style="--c:var(--service)">
            <h3>submission <span class="port">:9083</span></h3>
            <ul><li>Accept → 202</li><li>Store code in S3</li><li>Queue by mode</li><li>Verdict polling</li></ul>
          </div>
          <div class="block" style="--c:var(--service)">
            <h3>contest <span class="port">:9084</span></h3>
            <ul><li>Fests, registration</li><li>score.lua (ICPC)</li><li>5 s snapshots</li><li>Final standings</li></ul>
          </div>
          <div class="block" style="--c:var(--service)">
            <h3>analytics <span class="port">:9085</span></h3>
            <ul><li>Counters on verdict</li><li>Streaks, heatmap</li><li>Ready at login</li><li>Admin overview</li></ul>
          </div>
        </div>
      </section>

      <div class="link"><div></div><div class="wire"><span class="tag">SubmissionCreated</span><span class="tag up">VerdictReady</span></div></div>

      <!-- 4. Messaging -->
      <section class="layer">
        <div class="layer-name" style="--c:var(--queue)">Messaging<small>ElasticMQ :9702 · SQS on AWS</small></div>
        <div class="block" style="--c:var(--queue)">
          <div class="chips" style="margin-top:0">
            <span class="chip">contest-submissions</span>
            <span class="chip">practice-submissions</span>
            <span class="chip">verdict-events → analytics</span>
            <span class="chip">contest-verdicts → contest</span>
            <span class="chip">ingestion-jobs</span>
            <span class="chip">*-dlq after 3 attempts</span>
          </div>
          <p style="margin-top:8px">Separate contest queue so fest submissions never wait behind practice traffic. At-least-once delivery, idempotent consumers.</p>
        </div>
      </section>

      <div class="link"><div></div><div class="wire"><span class="tag">poll · long-polling consumers</span></div></div>

      <!-- 5. Execution -->
      <section class="layer">
        <div class="layer-name" style="--c:var(--exec)">Execution<small>no HTTP port · isolated</small></div>
        <div class="row exec-row">
          <div class="block" style="--c:var(--exec)">
            <h3>judge-worker</h3>
            <ul><li>Fetch code + tests</li><li>Run, compare outputs</li><li>Conditional verdict write</li><li>Scales on queue depth</li></ul>
          </div>
          <div class="block isolated" style="--c:var(--exec)">
            <h3>gVisor sandboxes <span class="port">runsc</span></h3>
            <p>One throw-away container per run</p>
            <div class="runners">
              <span class="runner">java</span><span class="runner">python</span><span class="runner">cpp</span><span class="runner">js</span><span class="runner">go</span>
            </div>
            <div class="chips">
              <span class="chip">--network=none</span><span class="chip">read-only root</span><span class="chip">pids 128</span>
              <span class="chip">cap-drop ALL</span><span class="chip">uid 65534</span><span class="chip">64 KB output</span>
            </div>
          </div>
          <div class="block" style="--c:var(--exec)">
            <h3>ingestion worker</h3>
            <p>Python · admin-triggered</p>
            <ul><li>RAG retrieve</li><li>Generate, dedupe 0.92</li><li>Verify in sandbox</li><li>Save IN_REVIEW</li></ul>
          </div>
          <div class="block external" style="--c:var(--ext)">
            <h3>LLM</h3>
            <p>Claude API, or offline mock</p>
            <ul><li>Ingestion only</li><li>Output never trusted</li></ul>
          </div>
        </div>
      </section>

      <div class="link"><div></div><div class="wire"><span class="tag">AWS SDK · same code locally and on AWS</span></div></div>

      <!-- 6. Data -->
      <section class="layer">
        <div class="layer-name" style="--c:var(--data)">Data<small>each table has one owner</small></div>
        <div class="row cols-4">
          <div class="block" style="--c:var(--data)">
            <h3>DynamoDB <span class="port">:9700</span></h3>
            <p>Users · Problems · Submissions · Contests · UserStats · UserSolved · IngestionRuns</p>
          </div>
          <div class="block" style="--c:var(--data)">
            <h3>Redis 7.4 <span class="port">:9701</span></h3>
            <p>Caches with TTL · rate limits · refresh tokens · deny list · leaderboard sorted sets</p>
          </div>
          <div class="block" style="--c:var(--data)">
            <h3>S3 <span class="port">:9703</span></h3>
            <p>Submitted source · hidden tests <code>&lt;slug&gt;/v&lt;n&gt;</code>, immutable</p>
          </div>
          <div class="block" style="--c:var(--data)">
            <h3>pgvector <span class="port">:9704</span></h3>
            <p>Problem embeddings for RAG retrieval and duplicate detection</p>
          </div>
        </div>
      </section>

    </main>

    <aside>
      <div class="card">
        <h4>Security</h4>
        <ul>
          <li><b>RS256 JWT</b>, signed only by auth</li>
          <li>Roles checked at gateway <b>and</b> service</li>
          <li>Refresh reuse revokes the family</li>
          <li>Expected outputs never enter the sandbox</li>
          <li>All local ports on 127.0.0.1</li>
        </ul>
      </div>
      <div class="card">
        <h4>Resilience</h4>
        <ul>
          <li>Circuit breakers at gateway and service-to-service</li>
          <li>Queue redelivery + DLQs</li>
          <li>SYSTEM_ERROR after 3 attempts</li>
          <li>Wall-clock kill for runaway code</li>
          <li>Idempotent submit and verdict write</li>
        </ul>
      </div>
      <div class="card">
        <h4>Scale targets</h4>
        <div class="stat"><span>registered users</span><b class="num">1M</b></div>
        <div class="stat"><span>fest concurrency</span><b class="num">100K</b></div>
        <div class="stat"><span>peak submissions/s</span><b class="num">~2.5K</b></div>
        <div class="stat"><span>leaderboard reads/s</span><b class="num">20K</b></div>
        <div class="stat"><span>leaderboard refresh</span><b class="num">5 s</b></div>
      </div>
    </aside>
  </div>

  <footer>
    <div class="card">
      <h4>Runs locally today</h4>
      <div class="map">
        <span class="chip">Docker Compose infra</span><span class="chip">java -jar services</span>
        <span class="chip">Vite dev server</span><span class="chip">gVisor runner images</span>
        <span class="chip">ElasticMQ</span><span class="chip">S3Mock</span><span class="chip">DynamoDB Local</span>
      </div>
    </div>
    <div class="card aws">
      <h4>Maps to AWS later</h4>
      <div class="map">
        <span class="chip">Route 53 + CloudFront + WAF</span><span class="chip">EKS</span><span class="chip">SQS + EventBridge</span>
        <span class="chip">EC2 Spot judge fleet</span><span class="chip">DynamoDB Global Tables</span>
        <span class="chip">ElastiCache</span><span class="chip">S3 CRR</span><span class="chip">RDS pgvector</span>
      </div>
    </div>
  </footer>

</div>
</body>
</html>
```

## Your requirements, and where each one is built

| # | Requirement | Chapter |
| --- | --- | --- |
| 1 | JWT login, authentication and authorization; gateway with routing, circuit breaker, rate limiter, security | 04, 05 |
| — | Two roles: ADMIN ingests problems with AI + RAG, USER solves and submits | 04, 05, 13, 14 |
| 2 | Problem list paginated at 100 | 06, 13 |
| 3 | Problem page with examples and an editor for several languages | 06, 13 |
| 4 | Editor pre-filled with starter code for every language | 03, 06 |
| 5 | 1 million users, 100,000 during a fest | 00 (capacity), 10, 15 |
| 6 | Live fest leaderboard | 10, 13 |
| 7 | NoSQL (DynamoDB), Redis cache with TTL, leaderboard refreshed every 5 s | 02, 06, 10 |
| 8 | User code isolated in containers with security controls | 07, 09 |
| 9 | AI + RAG pipeline to ingest 1,000+ problems with starter code | 14 |
| 10 | Fault tolerance against malicious code | 07, 09, 15 |
| 11 | Analytics service; stats shown at next login | 11, 13 |
| 12 | Globally distributed database on AWS | after this guide (the AWS plan in the CodeArena doc) |
| — | Best-in-class web UI; APIs reusable by mobile apps | 05, 13 |

A note on requirement 9. LeetCode's terms of service forbid scraping, and its problem statements are copyrighted, so
nothing here copies them. Chapter 14 builds the same AI + RAG pipeline to write *original* problems from a topic
list, verifies each one by running code in the sandbox, and puts it in front of an admin before publishing.
The seven seed problems in chapter 06 are original too.

## Chapters

| Chapter | You build | Check at the end |
| --- | --- | --- |
| 01 Prerequisites and repo | Tools, Gradle monorepo, convention plugins | `./gradlew help` works |
| 02 Local infrastructure | Docker Compose: DynamoDB Local, Redis, ElasticMQ, S3Mock, pgvector; scripts | `scripts/up.sh` seeds 7 problems |
| 03 Shared libraries | Events, roles, errors, DynamoDB helpers, starter-code generator | `./gradlew :libs:codegen:build` |
| 04 Auth service | JWT (RS256), refresh rotation, JWKS, admin roles | register, login, JWKS by curl |
| 05 API gateway | Routes, JWT + role checks, rate limits, circuit breakers | 403 for users on admin API, 429 on bursts |
| 06 Problem service | Catalog, 100-per-page, starter code, admin review, seed data | problem list through the gateway |
| 07 Sandbox runners | gVisor, one runner image per language | run a solution by hand in the sandbox |
| 08 Submission service | Accept, store, queue, poll | submission goes PENDING |
| 09 Judge worker | Sandbox execution, judging, red-team tests | correct code is ACCEPTED, a loop is TLE |
| 10 Contest service | Fests, ICPC scoring in Lua, 5-second snapshots | leaderboard updates |
| 11 Analytics service | Stats, streaks, heatmap, admin overview | stats after a solve |
| 12 Run everything | One-command start, end-to-end smoke test | `scripts/smoke.sh` all green |
| 13 Web app | React UI: list, workspace, fests, progress, admin console | the full app in the browser |
| 14 AI + RAG ingestion | Python worker: generate, retrieve, dedupe, verify, review | a run fills the review queue |
| 15 Testing and hardening | Unit, red-team, load tests; security checklist | k6 thresholds pass |
| 16 Troubleshooting | Symptoms and fixes | |

## Versions

Gradle 9.1 · Java 25 · Spring Boot 4.0.9 · Spring Cloud 2025.1.3 · Spring Cloud AWS 4.0.2 · Jackson 3 ·
Testcontainers 2 · React 19 · React Router 8 · Vite 8 · TypeScript 7 · Tailwind 4.3 · Python 3.13 · Node 24.
Runner images: Java 25, Python 3.13, GCC 15 (C++20), Node 24, Go 1.25. All versions live in
`build-logic/src/main/kotlin/Versions.kt` and `frontend/package.json`.

## Ports (all bound to 127.0.0.1)

| Port | What |
| --- | --- |
| 5173 | Web app (Vite dev server, proxies /api to the gateway) |
| 9080 | api-gateway, the only API entry point |
| 9081–9085 | auth, problem, submission, contest, analytics services |
| 9700 | DynamoDB Local |
| 9701 | Redis |
| 9702 / 9707 | ElasticMQ (SQS API / web console) |
| 9703 | S3Mock |
| 9704 | PostgreSQL + pgvector |
| 9705 / 9706 | DynamoDB admin, RedisInsight (optional: `TOOLS=1 scripts/up.sh`) |

Ports are in the 9xxx range on purpose so they do not clash with other local stacks (such as QuickBite on 8080).

## Capacity: what 1 million users and a 100,000-person fest mean

The design is sized for this; your laptop obviously runs a scaled-down version of it.

| Load | Estimate | Design answer |
| --- | --- | --- |
| Fest submissions | 100K users, one submit every ~2 min, about 830/s, bursts to ~2,500/s | Queue + async judging; nothing waits in the request path |
| Judge CPU | 830/s × ~0.5 s ≈ 415 busy CPUs at peak | Horizontally scaled judge hosts, separate contest queue |
| Leaderboard reads | 100K clients polling every 5 s = 20,000 req/s | One precomputed snapshot every 5 s, cacheable for 5 s |
| Problem list | Read-mostly | Whole list cached in Redis, paged in memory |
| Login burst at fest start | ~330/s | Stateless JWT; services verify with a cached public key |

## What was tested while writing this guide, and what you will test first

Run in the author's environment: the frontend type-checks and builds, and every page was reviewed in a headless
browser at desktop and phone widths. The starter-code and harness templates were rendered and executed with real
Python, Node, g++ and Go toolchains for every parameter type, and the Java harness compiles. The seed problems'
reference solutions were cross-checked against brute force on 300 random inputs each. The ingestion pipeline's
verification logic has unit tests (7 passing), and the extractor reproduces the repository byte for byte.

Not run there, because that environment had no Docker daemon and no Maven Central access: the Gradle build of the
Java services, the sandbox containers, and the full stack. The move to Gradle 9.1, Java 25, Spring Boot 4.0 and
Jackson 3 was done against the official compatibility tables and migration guides, but it has not been compiled yet:
your first `./gradlew build` is its first compile. Chapter 12's `scripts/smoke.sh` is your first
end-to-end proof; chapter 16 lists the fixes for what most often goes wrong.
