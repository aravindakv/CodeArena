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