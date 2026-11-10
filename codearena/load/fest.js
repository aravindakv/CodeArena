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