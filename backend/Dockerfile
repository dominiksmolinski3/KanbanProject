FROM eclipse-temurin:25-jdk-noble@sha256:589ff4cc3f71aab462e7048a47a0d10edf57fbccde3fceea2281e610bf5880b4 AS backend-build
WORKDIR /build

COPY pom.xml ./
COPY mvnw ./
COPY .mvn ./.mvn/
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
RUN ./mvnw --batch-mode --quiet dependency:go-offline

COPY src ./src/

RUN ./mvnw package -Dmaven.test.skip=true

FROM eclipse-temurin:25-jre-noble@sha256:d9a39a23634650173f1e2bbc176227af9728587ecf0f4b62d53e9355cd7a19ab
WORKDIR /app

# CD passes the date, or the registry cache replays yesterday's upgrade and the sweep fixes nothing.
ARG APT_UPGRADE_DATE
RUN apt-get update \
 && apt-get upgrade -y --no-install-recommends \
 && rm -rf /var/lib/apt/lists/*

RUN groupadd --system --gid 10001 appuser \
 && useradd --system --uid 10001 --gid 10001 --no-create-home appuser

COPY --from=backend-build --chown=10001:10001 /build/target/*.jar app.jar

ADD --checksum=sha256:93a70c8f5d364c7e777f6c4d1b235dba91aef8448bd3fa94359f1d7f3e2eb0ec --chown=10001:10001     https://repo1.maven.org/maven2/com/microsoft/azure/applicationinsights-agent/3.7.10/applicationinsights-agent-3.7.10.jar     applicationinsights-agent.jar
COPY --chown=10001:10001 applicationinsights.json ./

USER 10001:10001

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 CMD ["/bin/bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /actuator/health/readiness HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n' >&3 && grep -q '\"status\":\"UP\"' <&3"]

ENV JAVA_OPTS="-XX:MaxRAMPercentage=60.0"
ENTRYPOINT ["sh", "-c", "if [ -n \"$APPLICATIONINSIGHTS_CONNECTION_STRING\" ]; then AGENT=-javaagent:/app/applicationinsights-agent.jar; fi; exec java $AGENT $JAVA_OPTS -jar app.jar"]
