FROM eclipse-temurin:25-jdk-noble@sha256:264fafc3390db78c93dc51da0109a0d66ad1fb59f7a893f12b7e3df1f15e52da AS backend-build
WORKDIR /build

COPY pom.xml ./
COPY mvnw ./
COPY .mvn ./.mvn/
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw

COPY src ./src/

RUN ./mvnw package -Dmaven.test.skip=true

FROM eclipse-temurin:25-jre-noble@sha256:d120abd9d8d7dec94520ce974ece62d0e4eed8576eb00bbc84e6128307ab48ef
WORKDIR /app

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
