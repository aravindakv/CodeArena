# 07 · The sandbox: gVisor and one runner image per language

User code is the most dangerous input the platform has. It runs only inside a throw-away container with
defences stacked so that no single one has to be perfect:

| Layer | Setting | Stops |
| --- | --- | --- |
| Kernel isolation | `--runtime=runsc` (gVisor: a user-space kernel between the code and the host kernel) | kernel exploits, container escapes |
| No network | `--network=none` | data exfiltration, crypto-mining callbacks, attacks on your network or cloud metadata |
| Read-only root | `--read-only` + small `--tmpfs /tmp` | writing anywhere, filling the disk |
| Memory | `--memory` = `--memory-swap` | memory bombs; the OOM killer ends the run |
| CPU and time | `--cpus=1`, `timeout -s KILL` inside, wall-clock kill outside | infinite loops, starving other jobs |
| Processes | `--pids-limit=128` | fork bombs |
| Files | `--ulimit nofile=64`, `fsize=10 MB`, output capped at 64 KB | descriptor exhaustion, giant outputs |
| Privileges | `--user 65534`, `--cap-drop=ALL`, `no-new-privileges` | privilege escalation |
| Secrets | the answers (expected outputs) are never mounted; only inputs are | reading the answers |

## Step 1: install gVisor

Docker Desktop on macOS cannot run gVisor; use a Linux machine (yours is fine).

```bash
sudo apt-get install -y ca-certificates curl gnupg
curl -fsSL https://gvisor.dev/archive.key | sudo gpg --dearmor -o /usr/share/keyrings/gvisor-archive-keyring.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/gvisor-archive-keyring.gpg] https://storage.googleapis.com/gvisor/releases release main" \
  | sudo tee /etc/apt/sources.list.d/gvisor.list
sudo apt-get update && sudo apt-get install -y runsc
sudo runsc install && sudo systemctl reload docker
docker run --rm --runtime=runsc alpine dmesg | head -3      # gVisor's own boot messages prove it works
```

If you cannot install it right now, set `JUDGE_RUNTIME=runc` in `.env`: the Docker limits still apply, but there is
no kernel isolation. Treat that as development-only.

## Step 2: the runner contract

Every image runs `/runner/run.sh`, which sources a shared `common.sh`. The judge mounts `/work` read-only (user
file, harness file, `inputs.jsonl`) and `/out` writable. The program runs **once over all inputs** with the
problem's time limit for the whole test set (one JVM start instead of forty), and the harness writes one result line
per input; if the program crashes at test 18, the 17 lines already written tell the judge exactly where it failed.

**File:** `sandbox-images/common.sh`

```bash
#!/bin/bash
# Shared by every runner image (sourced by /runner/run.sh). Contract with judge-worker:
#   in : /work/<user file>, /work/<harness file>, /work/inputs.jsonl (one JSON test input per line)
#   out: /out/results.jsonl  written by the harness, one JSON result per input line
#        /out/stdout, /out/stderr  the program's own output (capped), shown to the user for debugging
#        /out/compile.err        compiler messages, if compilation failed
#        /out/meta               "COMPILE <exit>"  or  "RUN <exit> <elapsed ms>"
#        /out/maxrss_kb          peak memory of the program (from GNU time)
#   env: TIME_LIMIT_MS (whole test set), MAX_OUT_BYTES, MEMORY_MB
set -u
: "${TIME_LIMIT_MS:=2000}" "${MAX_OUT_BYTES:=65536}" "${MEMORY_MB:=256}"

compile_failed() {
  echo "COMPILE $1" > /out/meta
  exit 0
}

# run_program <command...>: runs it once over all inputs with the time limit.
run_program() {
  local secs start end status
  secs=$(printf '%d.%03d' $((TIME_LIMIT_MS / 1000)) $((TIME_LIMIT_MS % 1000)))
  start=$(date +%s%N)
  /usr/bin/time -f '%M' -o /out/maxrss_kb \
    timeout -s KILL "$secs" "$@" < /work/inputs.jsonl 2> /out/stderr \
    | head -c "$MAX_OUT_BYTES" > /out/stdout
  status=${PIPESTATUS[0]}
  end=$(date +%s%N)
  echo "RUN $status $(( (end - start) / 1000000 ))" > /out/meta
}
```

## Step 3: the images

| Language | Image | Compiler / runtime |
| --- | --- | --- |
| Java | `eclipse-temurin:25-jdk` | Java 25 (Jackson 2 jars for the hidden harness only) |
| Python | `python:3.13-slim` | CPython 3.13 |
| C++ | `gcc:15` | GCC 15, `-std=gnu++20`, nlohmann/json |
| JavaScript | `node:24-bookworm-slim` | Node 24 LTS |
| Go | `golang:1.25-bookworm` | Go 1.25 |

Compiled languages (C++, Go) need an executable `/tmp` and extra memory for the compiler; their run-time memory is
then checked from the measured peak. Go's standard library is pre-compiled into the image so each build takes about
a second.

**File:** `sandbox-images/python/Dockerfile`

```dockerfile
# Build from sandbox-images/:  docker build -t codearena/runner-python:1 -f python/Dockerfile .
FROM python:3.13-slim
RUN apt-get update && apt-get install -y --no-install-recommends time \
 && rm -rf /var/lib/apt/lists/*
COPY common.sh /runner/common.sh
COPY python/run.sh /runner/run.sh
RUN chmod 555 /runner/*.sh
USER 65534:65534
ENTRYPOINT ["/runner/run.sh"]
```

**File:** `sandbox-images/python/run.sh`

```bash
#!/bin/bash
source /runner/common.sh
# Syntax errors surface as a compile error, like in compiled languages.
if ! python3 -I -c 'import ast,sys; ast.parse(open("/work/solution.py").read())' 2> /out/compile.err; then
  compile_failed 1
fi
run_program python3 -I /work/harness.py
```

**File:** `sandbox-images/javascript/Dockerfile`

```dockerfile
# Build from sandbox-images/:  docker build -t codearena/runner-javascript:1 -f javascript/Dockerfile .
FROM node:24-bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends time \
 && rm -rf /var/lib/apt/lists/*
COPY common.sh /runner/common.sh
COPY javascript/run.sh /runner/run.sh
RUN chmod 555 /runner/*.sh
USER 65534:65534
ENTRYPOINT ["/runner/run.sh"]
```

**File:** `sandbox-images/javascript/run.sh`

```bash
#!/bin/bash
source /runner/common.sh
if ! node --check /work/solution.js 2> /out/compile.err; then
  compile_failed 1
fi
run_program node --max-old-space-size=$((MEMORY_MB * 3 / 4)) /work/harness.js
```

**File:** `sandbox-images/java/Dockerfile`

```dockerfile
# Build from sandbox-images/:  docker build -t codearena/runner-java:1 -f java/Dockerfile .
FROM eclipse-temurin:25-jdk
ARG JACKSON=2.17.2
RUN apt-get update && apt-get install -y --no-install-recommends time curl \
 && mkdir -p /opt/jackson && cd /opt/jackson \
 && for a in core/jackson-core core/jackson-databind core/jackson-annotations; do \
      n=${a#*/}; curl -fsSLO "https://repo1.maven.org/maven2/com/fasterxml/jackson/${a%%/*}/$n/$JACKSON/$n-$JACKSON.jar"; \
    done \
 && apt-get purge -y curl && rm -rf /var/lib/apt/lists/*
COPY common.sh /runner/common.sh
COPY java/run.sh /runner/run.sh
RUN chmod 555 /runner/*.sh
USER 65534:65534
ENTRYPOINT ["/runner/run.sh"]
```

**File:** `sandbox-images/java/run.sh`

```bash
#!/bin/bash
source /runner/common.sh
mkdir -p /tmp/b
if ! timeout -s KILL 20 javac -J-Xmx256m -J-XX:-UsePerfData -encoding UTF-8 -cp '/opt/jackson/*' \
     -d /tmp/b /work/Solution.java /work/Main.java 2> /out/compile.err; then
  compile_failed 1
fi
run_program java -Xmx$((MEMORY_MB * 3 / 4))m -Xss64m -XX:+UseSerialGC -XX:-UsePerfData \
  -cp "/tmp/b:/opt/jackson/*" Main
```

**File:** `sandbox-images/cpp/Dockerfile`

```dockerfile
# Build from sandbox-images/:  docker build -t codearena/runner-cpp:1 -f cpp/Dockerfile .
FROM gcc:15
RUN apt-get update && apt-get install -y --no-install-recommends time nlohmann-json3-dev \
 && rm -rf /var/lib/apt/lists/*
COPY common.sh /runner/common.sh
COPY cpp/run.sh /runner/run.sh
RUN chmod 555 /runner/*.sh
USER 65534:65534
ENTRYPOINT ["/runner/run.sh"]
```

**File:** `sandbox-images/cpp/run.sh`

```bash
#!/bin/bash
source /runner/common.sh
mkdir -p /tmp/b
# main.cpp (the harness) #includes solution.cpp, so this compiles both.
if ! timeout -s KILL 30 g++ -O2 -std=gnu++20 -pipe -o /tmp/b/sol /work/main.cpp 2> /out/compile.err; then
  compile_failed 1
fi
run_program /tmp/b/sol
```

**File:** `sandbox-images/go/Dockerfile`

```dockerfile
# Build from sandbox-images/:  docker build -t codearena/runner-go:1 -f go/Dockerfile .
FROM golang:1.25-bookworm
RUN apt-get update && apt-get install -y --no-install-recommends time \
 && rm -rf /var/lib/apt/lists/*
# Pre-compile the standard library packages the harness uses, so each submission builds in ~1 s.
ENV CGO_ENABLED=0
RUN mkdir -p /tmp/warm && cd /tmp/warm \
 && printf 'package main\nimport ("bufio";"encoding/json";"os";"sort";"strings";"strconv";"math";"container/heap")\nvar _ = bufio.NewScanner\nvar _ = json.Marshal\nvar _ = os.Exit\nvar _ = sort.Ints\nvar _ = strings.Split\nvar _ = strconv.Itoa\nvar _ = math.Max\nvar _ = heap.Init\nfunc main(){}\n' > main.go \
 && GOCACHE=/opt/gocache go build -o /dev/null main.go && chmod -R a+rX /opt/gocache && rm -rf /tmp/warm
COPY common.sh /runner/common.sh
COPY go/run.sh /runner/run.sh
RUN chmod 555 /runner/*.sh
USER 65534:65534
ENTRYPOINT ["/runner/run.sh"]
```

**File:** `sandbox-images/go/run.sh`

```bash
#!/bin/bash
source /runner/common.sh
mkdir -p /tmp/b
# The root filesystem is read-only, so copy the pre-warmed build cache into the writable /tmp.
cp -r /opt/gocache /tmp/gocache
export GOCACHE=/tmp/gocache GOPATH=/tmp/gopath GOMAXPROCS=2 CGO_ENABLED=0 HOME=/tmp
if ! timeout -s KILL 30 go build -o /tmp/b/sol /work/solution.go /work/harness.go 2> /out/compile.err; then
  compile_failed 1
fi
run_program /tmp/b/sol
```

**File:** `sandbox-images/build-all.sh`

```bash
#!/usr/bin/env bash
# Builds every runner image. Run from anywhere: sandbox-images/build-all.sh [lang...]
set -euo pipefail
cd "$(dirname "$0")"
langs=("$@"); [[ ${#langs[@]} -eq 0 ]] && langs=(python javascript java cpp go)
for l in "${langs[@]}"; do
  echo "==> codearena/runner-$l:1"
  docker build -t "codearena/runner-$l:1" -f "$l/Dockerfile" .
done
```

```bash
chmod +x sandbox-images/*.sh sandbox-images/*/run.sh
sandbox-images/build-all.sh          # 5 images; Java and Go take a few minutes the first time
```

## Check: run a solution by hand, exactly as the judge will

```bash
mkdir -p /tmp/ca-try/{work,out} && cd /tmp/ca-try
cat > work/solution.py <<'PY'
class Solution:
    def pairWithTargetSum(self, nums, target):
        first = {}
        for j, x in enumerate(nums):
            if target - x in first:
                return [first[target - x], j]
            first.setdefault(x, j)
PY
cat > work/harness.py <<'PY'
import sys as ca_sys, json as ca_json
from typing import *
ca_res = open("/out/results.jsonl", "w", buffering=1)
exec(compile(open("/work/solution.py").read(), "solution.py", "exec"))
for ca_line in ca_sys.stdin:
    if ca_line.strip():
        ca_a = ca_json.loads(ca_line)
        ca_res.write(ca_json.dumps(Solution().pairWithTargetSum(ca_a["nums"], ca_a["target"])) + "\n")
PY
printf '%s\n' '{"nums":[3,1,4,6],"target":7}' '{"nums":[5,5],"target":10}' > work/inputs.jsonl
chmod -R a+rX work && chmod 777 out
docker run --rm --runtime=runsc --network=none --read-only --tmpfs /tmp:rw,noexec,size=64m \
  --memory=256m --memory-swap=256m --cpus=1 --pids-limit=128 --cap-drop=ALL --security-opt=no-new-privileges \
  --user 65534:65534 -e TIME_LIMIT_MS=2000 -v $PWD/work:/work:ro -v $PWD/out:/out codearena/runner-python:1
cat out/results.jsonl out/meta        # [0, 2]  [0, 1]   then   RUN 0 <milliseconds>
```

Now replace the method body with `while True: pass` and run again: `out/meta` shows `RUN 137 2000`-ish (killed at
the limit), and `results.jsonl` is empty.
