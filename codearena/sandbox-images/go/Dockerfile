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