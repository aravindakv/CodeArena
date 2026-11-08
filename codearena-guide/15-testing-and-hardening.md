# 15 · Testing, load, and hardening

## The test pyramid you now have

| Level | Command | Covers |
| --- | --- | --- |
| Unit | `./gradlew test` | key loading, example parsing, comparators, verdict rules, streaks |
| Integration (Testcontainers) | `./gradlew :services:contest-service:test` | the real Lua script on real Redis |
| Sandbox red team | `./gradlew :services:judge-worker:test -Psandbox` | 11 attacks and failures against the real sandbox |
| Ingestion logic | `cd ingestion && uv run pytest` | example checks, brute-force cross-check, dedupe |
| Seed data | `scripts/py.sh scripts/seed.py --check` | every example, reference vs brute force |
| End to end | `scripts/smoke.sh` | the whole platform through the gateway, as user and admin |
| Load | `k6 run load/fest.js` | a fest on your machine |

## Malicious code: what each attack meets

| Attack | Stopped by | Verdict |
| --- | --- | --- |
| `while True: pass` | per-run `timeout`, outer wall clock | TIME_LIMIT_EXCEEDED |
| Fork bomb | `--pids-limit=128` | RUNTIME_ERROR |
| Allocate gigabytes | `--memory`, no swap | MEMORY_LIMIT_EXCEEDED |
| Open a socket, call home, read cloud metadata | `--network=none` | RUNTIME_ERROR |
| Write to `/etc`, fill the disk | read-only root, 64 MB tmpfs, `fsize` limit | RUNTIME_ERROR |
| Print forever | 64 KB output cap | OUTPUT_LIMIT_EXCEEDED |
| Read the expected answers | never mounted; only inputs enter the container | wrong answer |
| Exploit the kernel / escape | gVisor user-space kernel, no capabilities, non-root | contained |
| Crash the judge repeatedly | SQS retries, SYSTEM_ERROR after 3 attempts, DLQ | judge stays up |
| Flood submissions | gateway rate limit, 1/s per user | 429 |

## Load test a fest locally

**File:** `load/fest.js`

```javascript
// Local fest simulation with k6 (https://k6.io): many users polling the leaderboard and submitting.
// Scale it to your machine: the judge, not the API, is the bottleneck on one laptop.
//   scripts/py.sh load/make_users.py 200        # creates users and writes load/tokens.json
//   k6 run -e CONTEST=<id> -e VUS=200 load/fest.js
import http from "k6/http";
import { check, sleep } from "k6";
import { SharedArray } from "k6/data";

const BASE = __ENV.BASE || "http://localhost:9080/api/v1";
const CONTEST = __ENV.CONTEST;
const VUS = Number(__ENV.VUS || 100);
const tokens = new SharedArray("tokens", () => JSON.parse(open("./tokens.json")));

const SOLUTION = `class Solution:
    def pairWithTargetSum(self, nums, target):
        first = {}
        for j, x in enumerate(nums):
            if target - x in first:
                return [first[target - x], j]
            first.setdefault(x, j)
`;

export const options = {
  scenarios: {
    fest: { executor: "ramping-vus", startVUs: 0, stages: [
      { duration: "1m", target: VUS }, { duration: "3m", target: VUS }, { duration: "30s", target: 0 }] },
  },
  thresholds: {
    "http_req_duration{name:leaderboard}": ["p(95)<200"],
    "http_req_failed{name:leaderboard}": ["rate<0.01"],
    "checks{name:submit}": ["rate>0.95"],
  },
};

export function setup() {
  if (!CONTEST) throw new Error("pass -e CONTEST=<contest id> (create one in the admin console)");
  // Register every user for the contest once.
  for (const t of tokens) http.post(`${BASE}/contests/${CONTEST}/register`, null, { headers: { Authorization: `Bearer ${t}` } });
}

export default function () {
  const token = tokens[(__VU - 1) % tokens.length];
  const auth = { Authorization: `Bearer ${token}`, "Content-Type": "application/json" };
  // A competitor refreshes the board every 5 s and submits about once a minute.
  for (let i = 0; i < 12; i++) {
    const lb = http.get(`${BASE}/contests/${CONTEST}/leaderboard`, { tags: { name: "leaderboard" } });
    check(lb, { "leaderboard 200": (r) => r.status === 200 });
    if (i === 0) {
      const r = http.post(`${BASE}/submissions`, JSON.stringify({ slug: "pair-with-target-sum", lang: "python",
        mode: "SUBMIT", code: SOLUTION, contestId: CONTEST }), { headers: auth, tags: { name: "submit" } });
      check(r, { "submit accepted or rate limited": (x) => x.status === 202 || x.status === 429 }, { name: "submit" });
    }
    sleep(5);
  }
}
```

**File:** `load/make_users.py`

```python
#!/usr/bin/env python3
"""Creates N load-test users through the gateway and writes their access tokens to load/tokens.json.
The gateway limits login/register per IP, so this paces itself (about one user every 2 seconds)."""
import json
import sys
import time
from pathlib import Path

import requests

API = "http://localhost:9080/api/v1"
n = int(sys.argv[1]) if len(sys.argv) > 1 else 100
tokens = []
for i in range(n):
    email, pw = f"load{i}@codearena.local", "load-test-password"
    while True:
        requests.post(f"{API}/auth/register", json={"email": email, "password": pw, "displayName": f"load_{i}"})
        r = requests.post(f"{API}/auth/login", json={"email": email, "password": pw})
        if r.status_code == 429:
            time.sleep(1.1)
            continue
        r.raise_for_status()
        tokens.append(r.json()["accessToken"])
        break
    print(f"\r{i + 1}/{n}", end="", flush=True)
    time.sleep(2.1)   # register + login = 2 requests; the per-IP limit refills 1 per second
out = Path(__file__).parent / "tokens.json"
out.write_text(json.dumps(tokens))
print(f"\nwrote {out} (tokens expire in 15 minutes; rerun before a long test)")
```

```bash
sudo snap install k6                                   # or see https://k6.io/docs/get-started/installation/
scripts/py.sh load/make_users.py 100                   # ~3.5 minutes: the login rate limit paces it
# Admin console → Fests → create one starting now with pair-with-target-sum; copy its id
k6 run -e CONTEST=<id> -e VUS=100 load/fest.js
```

What to look for: leaderboard p95 under 200 ms no matter how many virtual users (it is one Redis GET), submissions
accepted or rate-limited but never failing, and the judge queue draining (watch http://localhost:9707). On one machine
the judge is the bottleneck: raise `JUDGE_CONCURRENCY` up to your CPU count and watch the queue age fall.

## Hardening checklist before anything leaves your machine

- [ ] `BOOTSTRAP_ADMIN_EMAIL` is empty; admins are granted only in the console.
- [ ] `JUDGE_RUNTIME=runsc` everywhere user code runs.
- [ ] `COOKIE_SECURE=true` behind HTTPS.
- [ ] The JWT key comes from a secret store, with a fixed `kid` and a rotation plan (publish two keys in JWKS during rotation).
- [ ] `/internal/**` reachable only from inside the cluster (network policy), never routed by the gateway.
- [ ] Problem statements are sanitized on render (`rehype-sanitize`), because generated content is untrusted.
- [ ] Dependency and image scanning in CI (for example `trivy fs .` and `trivy image codearena/runner-python:1`).
- [ ] The red-team suite runs in CI on a host with gVisor.
