# Build from sandbox-images/:  docker build -t codearena/runner-javascript:1 -f javascript/Dockerfile .
FROM node:24-bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends time \
 && rm -rf /var/lib/apt/lists/*
COPY common.sh /runner/common.sh
COPY javascript/run.sh /runner/run.sh
RUN chmod 555 /runner/*.sh
USER 65534:65534
ENTRYPOINT ["/runner/run.sh"]