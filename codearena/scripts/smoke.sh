#!/usr/bin/env bash
# End-to-end check of the whole local stack through the gateway (like a user and an admin would use it).
# Needs: scripts/up.sh, sandbox-images/build-all.sh python, scripts/services.sh start.
#   scripts/smoke.sh
set -euo pipefail
API=${API:-http://localhost:9080/api/v1}
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[[ -f $ROOT/.env ]] && { set -a; source "$ROOT/.env"; set +a; }
ADMIN_EMAIL=${BOOTSTRAP_ADMIN_EMAIL:-admin@codearena.local}
PASS='smoke-test-pass-1'                           # password of the throw-away test users
ADMIN_PASS=${ADMIN_PASSWORD:-change-me-please}     # the admin you registered in chapter 04
RUN=$RANDOM
pass=0; fail=0

j() { python3 -c 'import sys,json; d=json.load(sys.stdin);
for k in sys.argv[1].split("."): d = d[int(k)] if k.isdigit() else d[k]
print(d)' "$1"; }
ok() { echo "  ok   $1"; pass=$((pass + 1)); }
bad() { echo "  FAIL $1" >&2; fail=$((fail + 1)); }
expect() { [[ "$2" == "$3" ]] && ok "$1" || bad "$1: expected '$3', got '$2'"; }

register() {   # email name [password]
  curl -s -o /dev/null -w '%{http_code}' -X POST "$API/auth/register" -H 'content-type: application/json' \
    -d "{\"email\":\"$1\",\"password\":\"${3:-$PASS}\",\"displayName\":\"$2\"}"; }
login() {      # email [password] -> access token, or nothing if the login fails
  local out
  out=$(curl -s -X POST "$API/auth/login" -H 'content-type: application/json' \
    -d "{\"email\":\"$1\",\"password\":\"${2:-$PASS}\"}") || true
  python3 -c 'import sys,json
try: print(json.loads(sys.stdin.read())["accessToken"])
except Exception: pass' <<<"$out"; }

submit() {   # token slug lang mode code-file [contestId] -> submission id
  local body
  body=$(python3 -c 'import json,sys; print(json.dumps({"slug":sys.argv[1],"lang":sys.argv[2],"mode":sys.argv[3],
        "code":open(sys.argv[4]).read(), **({"contestId":sys.argv[5]} if len(sys.argv)>5 and sys.argv[5] else {})}))' \
        "$2" "$3" "$4" "$5" "${6:-}")
  curl -sf -X POST "$API/submissions" -H "authorization: Bearer $1" -H 'content-type: application/json' \
    -H "idempotency-key: $(python3 -c 'import uuid;print(uuid.uuid4())')" -d "$body" | j submissionId
}
verdict() {  # token id -> final status (waits up to 60 s)
  for _ in $(seq 1 60); do
    s=$(curl -sf "$API/submissions/$2" -H "authorization: Bearer $1" | j status)
    [[ $s != PENDING ]] && { echo "$s"; return; }
    sleep 1
  done
  echo TIMEOUT
}

echo "== auth"
expect "register admin (or already exists)" "$(register "$ADMIN_EMAIL" admin "$ADMIN_PASS" | sed 's/409/201/')" 201
expect "register user" "$(register "user$RUN@codearena.local" "user$RUN")" 201
ADMIN=$(login "$ADMIN_EMAIL" "$ADMIN_PASS")
[[ -n $ADMIN ]] || { bad "login $ADMIN_EMAIL (admin password wrong? run: ADMIN_PASSWORD=... scripts/smoke.sh)"; exit 1; }
USER=$(login "user$RUN@codearena.local")
[[ -n $USER ]] || { bad "login user$RUN@codearena.local"; exit 1; }
ok "login both"
expect "user is blocked from admin API" \
  "$(curl -s -o /dev/null -w '%{http_code}' "$API/admin/problems" -H "authorization: Bearer $USER")" 403
expect "admin reaches admin API" \
  "$(curl -s -o /dev/null -w '%{http_code}' "$API/admin/problems" -H "authorization: Bearer $ADMIN")" 200

echo "== problems"
expect "list returns 100-per-page shape" "$(curl -sf "$API/problems?page=1&size=100" | j page)" 1
expect "detail has examples" "$(curl -sf "$API/problems/pair-with-target-sum" | j examples.0.output)" "[0,2]"
starter=$(curl -sf "$API/problems/pair-with-target-sum/starter/java")
[[ $starter == *"public int[] pairWithTargetSum(int[] nums, int target)"* ]] && ok "java starter code" || bad "java starter: $starter"

echo "== judge"
tmp=$(mktemp -d)
cat > "$tmp/good.py" <<'PY'
class Solution:
    def pairWithTargetSum(self, nums, target):
        first = {}
        for j, x in enumerate(nums):
            if target - x in first:
                return [first[target - x], j]
            first.setdefault(x, j)
PY
cat > "$tmp/wrong.py" <<'PY'
class Solution:
    def pairWithTargetSum(self, nums, target):
        return [0, 1]
PY
cat > "$tmp/loop.py" <<'PY'
class Solution:
    def pairWithTargetSum(self, nums, target):
        while True:
            pass
PY
expect "RUN on examples" "$(verdict "$USER" "$(submit "$USER" pair-with-target-sum python RUN "$tmp/good.py")")" ACCEPTED
sleep 1.2
expect "SUBMIT correct" "$(verdict "$USER" "$(submit "$USER" pair-with-target-sum python SUBMIT "$tmp/good.py")")" ACCEPTED
sleep 1.2
expect "SUBMIT wrong" "$(verdict "$USER" "$(submit "$USER" pair-with-target-sum python SUBMIT "$tmp/wrong.py")")" WRONG_ANSWER
sleep 1.2
expect "SUBMIT infinite loop" "$(verdict "$USER" "$(submit "$USER" pair-with-target-sum python SUBMIT "$tmp/loop.py")")" TIME_LIMIT_EXCEEDED

echo "== contest + leaderboard"
start=$(python3 -c 'import datetime as d; print((d.datetime.now(d.timezone.utc)-d.timedelta(seconds=5)).strftime("%Y-%m-%dT%H:%M:%SZ"))')
end=$(python3 -c 'import datetime as d; print((d.datetime.now(d.timezone.utc)+d.timedelta(minutes=30)).strftime("%Y-%m-%dT%H:%M:%SZ"))')
CID=$(curl -sf -X POST "$API/admin/contests" -H "authorization: Bearer $ADMIN" -H 'content-type: application/json' \
  -d "{\"title\":\"Smoke fest $RUN\",\"startAt\":\"$start\",\"endAt\":\"$end\",\"problems\":[\"pair-with-target-sum\"]}" | j id)
[[ -n $CID ]] && ok "admin created contest $CID" || bad "create contest"
expect "user registers" "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/contests/$CID/register" -H "authorization: Bearer $USER")" 204
sleep 1.2
expect "contest submission" "$(verdict "$USER" "$(submit "$USER" pair-with-target-sum python SUBMIT "$tmp/good.py" "$CID")")" ACCEPTED
echo "  waiting 6 s for the leaderboard snapshot..."
sleep 6
expect "leaderboard shows the user" "$(curl -sf "$API/contests/$CID/leaderboard" | j rows.0.name)" "user$RUN"
expect "my rank" "$(curl -sf "$API/contests/$CID/leaderboard/me" -H "authorization: Bearer $USER" | j rank)" 1

echo "== stats (analytics)"
sleep 2
expect "solved count" "$(curl -sf "$API/me/stats" -H "authorization: Bearer $USER" | j solvedTotal)" 1

rm -rf "$tmp"
echo
echo "passed: $pass  failed: $fail"
[[ $fail == 0 ]]
