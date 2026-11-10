// Local fest simulation with k6 (https://k6.io): many users polling the leaderboard and submitting.
// Scale it to your machine: the judge, not the API, is the bottleneck on one laptop.
//   scripts/py.sh load/make_users.py 200        # creates users once and writes load/users.json
//   k6 run -e CONTEST=<id> -e VUS=200 load/fest.js
// setup() logs every user in (about one a second, the gateway's per-IP login limit), so tokens are fresh.
import http from "k6/http";
import { check, sleep } from "k6";
import { SharedArray } from "k6/data";

const BASE = __ENV.BASE || "http://localhost:9080/api/v1";
const CONTEST = __ENV.CONTEST;
const VUS = Number(__ENV.VUS || 100);
const users = new SharedArray("users", () => JSON.parse(open("./users.json")));

const SOLUTION = `class Solution:
    def pairWithTargetSum(self, nums, target):
        first = {}
        for j, x in enumerate(nums):
            if target - x in first:
                return [first[target - x], j]
            first.setdefault(x, j)
`;

export const options = {
  setupTimeout: "15m",   // logging in 200 users at the login rate limit takes a few minutes
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

function login(u) {
  for (;;) {
    const r = http.post(`${BASE}/auth/login`, JSON.stringify(u), { headers: { "Content-Type": "application/json" },
      tags: { name: "setup" } });
    if (r.status === 200) return r.json("accessToken");
    if (r.status !== 429) throw new Error(`login ${u.email}: ${r.status} ${r.body}`);
    sleep(1.1);   // rate limited: wait for the bucket to refill
  }
}

export function setup() {
  if (!CONTEST) throw new Error("pass -e CONTEST=<contest id> (create one in the admin console)");
  const c = http.get(`${BASE}/contests/${CONTEST}`, { tags: { name: "setup" } });
  if (c.status !== 200) throw new Error(`contest ${CONTEST}: ${c.status} ${c.body}`);
  const n = Math.min(users.length, VUS);
  console.log(`logging in ${n} users (about one a second)...`);
  const tokens = [];
  for (let i = 0; i < n; i++) {
    const token = login(users[i]);
    const r = http.post(`${BASE}/contests/${CONTEST}/register`, null,
      { headers: { Authorization: `Bearer ${token}` }, tags: { name: "setup" } });
    if (r.status !== 204 && r.status !== 200) throw new Error(`register for contest: ${r.status} ${r.body}`);
    tokens.push(token);
  }
  return { tokens };
}

let reported = false;   // print the first failed submission of each VU, so a failure explains itself

export default function (data) {
  const token = data.tokens[(__VU - 1) % data.tokens.length];
  const auth = { Authorization: `Bearer ${token}`, "Content-Type": "application/json" };
  // A competitor refreshes the board every 5 s and submits about once a minute.
  for (let i = 0; i < 12; i++) {
    const lb = http.get(`${BASE}/contests/${CONTEST}/leaderboard`, { tags: { name: "leaderboard" } });
    check(lb, { "leaderboard 200": (r) => r.status === 200 });
    if (i === 0) {
      const r = http.post(`${BASE}/submissions`, JSON.stringify({ slug: "pair-with-target-sum", lang: "python",
        mode: "SUBMIT", code: SOLUTION, contestId: CONTEST }), { headers: auth, tags: { name: "submit" } });
      const ok = check(r, { "submit accepted or rate limited": (x) => x.status === 202 || x.status === 429 }, { name: "submit" });
      if (!ok && !reported) { reported = true; console.warn(`submit failed: ${r.status} ${r.body}`); }
    }
    sleep(5);
  }
}
