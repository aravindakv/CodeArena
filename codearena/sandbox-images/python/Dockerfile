# Build from sandbox-images/:  docker build -t codearena/runner-python:1 -f python/Dockerfile .
FROM python:3.13-slim
RUN apt-get update && apt-get install -y --no-install-recommends time \
 && rm -rf /var/lib/apt/lists/*
COPY common.sh /runner/common.sh
COPY python/run.sh /runner/run.sh
RUN chmod 555 /runner/*.sh
USER 65534:65534
ENTRYPOINT ["/runner/run.sh"]