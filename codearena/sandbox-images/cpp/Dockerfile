# Build from sandbox-images/:  docker build -t codearena/runner-cpp:1 -f cpp/Dockerfile .
FROM gcc:15
RUN apt-get update && apt-get install -y --no-install-recommends time nlohmann-json3-dev \
 && rm -rf /var/lib/apt/lists/*
COPY common.sh /runner/common.sh
COPY cpp/run.sh /runner/run.sh
RUN chmod 555 /runner/*.sh
USER 65534:65534
ENTRYPOINT ["/runner/run.sh"]