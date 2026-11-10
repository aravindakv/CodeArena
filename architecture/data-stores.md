# CodeArena: why each data store

CodeArena uses four kinds of storage, plus a queue, and each one matches how its data is read and written. The rule
is: work out the questions the app asks of the data, how often it asks them, and how big the data is, then pick the
store that answers those questions cheaply.

| Data | Store | The question it answers | Why this store |
| --- | --- | --- | --- |
| Users, problems, submissions, contests, stats | DynamoDB | "get by key", "list by key, newest first" | constant speed at any scale, conditional and atomic writes, multi-region |
| Leaderboard, caches, rate limits, tokens | Redis | "top 100 by score", "is this request allowed?" | in memory, sorted sets, built-in expiry |
| Hidden tests, source code | S3 | "give me this file" | large immutable files, cheap, versioned paths |
| Problem embeddings | pgvector | "which problems are most similar?" | nearest-neighbour search, small and off the busy path |
| Submission and verdict events | SQS | "process this later, exactly as fast as you can" | absorbs bursts, retries, separate fest lane |

## DynamoDB: records the platform must keep

Users, problems, submissions, contests and per-user stats.

**Why it fits.** Almost every question the app asks is a direct key lookup or a range within one key. Examples are
"get user 42", "get problem `balanced-brackets`" and "list this user's last 20 submissions". DynamoDB answers these in
single-digit milliseconds whether there are a thousand rows or a billion. There are no servers to size, and on AWS
Global Tables copy the data to several regions with writes accepted in all of them. That covers the requirements for
1M users and a globally distributed database.

**Examples from CodeArena**

- **A user's submission history.** One partition per user, sorted by a time-ordered ID:

  ```
  PK = USER#42    SK = SUB#01JC9...   status=ACCEPTED, lang=python, ms=42
  ```

  "My recent submissions" is a single query on `PK = USER#42`, newest first, with a limit. It's equally fast for a
  user with 5 submissions or 50,000.
- **The verdict write, which must never happen twice.** The judge writes "set verdict only if status is still
  PENDING" as a conditional write. If the queue redelivers the message, the second write is simply rejected, with no
  locking code.
- **Stats counters.** `ADD solvedEasy 1, langPython 1` on `PK = USER#42, SK = STATS` is atomic. Two verdicts arriving
  at once for the same user can't lose an update. Each heatmap day is its own item (`SK = DAY#2026-11-10`).
- **Run-mode submissions clean themselves up.** They carry a `ttl` attribute, so DynamoDB deletes them after a day at
  no cost.

**Why not a relational database here.** PostgreSQL would work at small scale. But 100K fest users writing about
2,500 submissions per second, plus multi-region writes, means sharding and replication work you'd do yourself.

**The trade-off.** DynamoDB has no joins and no ad-hoc queries, so every access pattern is designed up front. That's
why analytics uses precomputed counters instead of `SELECT COUNT(*) ... GROUP BY` at read time.

## Redis: hot, short-lived or rapidly changing data

Caches, rate limits, tokens and the fest leaderboard.

**Why it fits.** Redis keeps everything in memory, so reads and writes take well under a millisecond. It has built-in
expiry on every key, and data structures that do the work for you: sorted sets, counters, atomic Lua scripts. The rule
for what goes in it: anything that can be rebuilt, or whose loss is harmless.

**Examples from CodeArena**

- **The fest leaderboard, the main reason Redis is here.** A sorted set keeps members ordered by score at all times:

  ```
  ZINCRBY lb:{c7}  2000000-35  user42       # solved one more, 35 penalty minutes
  ZREVRANGE lb:{c7} 0 99 WITHSCORES          # top 100, already sorted
  ```

  Each update costs O(log N), and reading the top 100 costs about the same. In SQL, the board would be
  `ORDER BY score DESC LIMIT 100` over a constantly changing table, run by 100K people every 5 seconds: about 20,000
  queries per second. Instead, one pod writes a JSON snapshot every 5 seconds (`lb:snap:{c7}`, which expires after
  10 s), and every viewer does one `GET`.
- **Rate limiting.** The gateway keeps a token bucket per user and per IP. Every request reads and updates it, so it
  has to be sub-millisecond, and the buckets are worthless after a few seconds anyway.
- **The problem-list cache.** The first page of 100 problems is the same for everyone and changes only when an admin
  publishes. It's cached as one entry with a TTL, and publishing bumps a version number so everyone sees the new list
  immediately.
- **Refresh tokens and the logout deny list.** Each key expires exactly when the token would (`EX 604800`). Losing
  them only forces users to log in again.

**Why not use it for everything.** Memory is expensive, and Redis isn't built as a permanent record. Submissions and
user accounts must survive anything, so they live in DynamoDB. Redis holds copies, counters and short-lived state.

## S3: large or immutable files

Hidden test cases and submitted source code.

**Why it fits.** S3 stores files of any size cheaply and very durably. It's ideal for data that's written once and
read many times.

**Examples from CodeArena**

- **Hidden test cases.** A problem's tests can be several MB (big inputs that check time limits), and a DynamoDB item
  can hold at most 400 KB. Tests are stored as immutable versions: `balanced-brackets/v3/cases.jsonl`. The judge
  caches them on disk by version, and a running fest never sees tests change halfway through, because v3 never
  changes; an update becomes v4.
- **Submitted source.** Code is capped at 64 KB, so it would fit in DynamoDB, but it's needed only by the judge.
  Keeping it in S3 (`src/01JC9...`) keeps the submission records small, so listing your history doesn't pull
  megabytes of code through the database.

**Why not a database.** A database charges for capacity based on item size and limits how big items can be. S3
charges very little per GB, has no practical size limit, and on AWS copies files to other regions with cross-region
replication.

## pgvector (PostgreSQL): similarity search for AI ingestion

**Why it fits.** The AI pipeline asks a question none of the other stores can answer: "which existing problems mean
something similar to this one?" Each problem is turned into an embedding, a list of about 1,000 numbers. pgvector
finds the nearest ones with an index built for that kind of search:

```sql
SELECT slug, 1 - (embedding <=> :new) AS similarity
FROM problem_embeddings ORDER BY embedding <=> :new LIMIT 3;
```

It's used twice:

- **Retrieval (the "R" in RAG):** the 3 nearest problems go into the LLM prompt, so the model sees the house style and
  avoids repeating them.
- **Duplicate detection:** a new draft with similarity ≥ 0.92 to an existing problem is rejected.

**Why not DynamoDB or Redis.** DynamoDB can't search for "closest" items at all, only exact keys and ranges. Redis has
vector-search modules, but they keep everything in memory. pgvector is also off the busy path: only the admin's
ingestion runs touch it, a few thousand rows at a time. Regular PostgreSQL is simple, durable and needs no special
service.

## SQS: not a database, but it's what lets the others work

Submissions go through a queue instead of straight to the judge. A burst of 2,500 submissions per second then piles
up safely in the queue rather than knocking over the judge. Failed messages are retried and end up in a dead-letter
queue after 3 attempts. Contest traffic has its own queue so fest submissions never wait behind practice ones.

## The result on a fest day

This combination is what keeps the busy path cheap:

- each leaderboard view is one Redis lookup;
- each submission is one S3 write, one DynamoDB write and one queue message;
- stats were already computed before the user logs in.

## Local and AWS

| Local (Docker Compose) | AWS |
| --- | --- |
| DynamoDB Local :9700 | DynamoDB Global Tables |
| Redis :9701 | ElastiCache for Redis (cluster mode) + Global Datastore |
| S3Mock :9703 | S3 + cross-region replication |
| pgvector :9704 | RDS for PostgreSQL with pgvector |
| ElasticMQ :9702 | SQS |

The services use the standard AWS SDK and Redis/JDBC clients throughout, so only endpoints and credentials change.
