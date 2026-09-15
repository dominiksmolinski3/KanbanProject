# Stage 1: Build the jar
FROM eclipse-temurin:25-jdk-noble@sha256:264fafc3390db78c93dc51da0109a0d66ad1fb59f7a893f12b7e3df1f15e52da AS backend-build
WORKDIR /build

# Copy the build files first so the wrapper layer survives source-only changes
COPY pom.xml ./
COPY mvnw ./
COPY .mvn ./.mvn/
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw

COPY src ./src/

RUN ./mvnw package -Dmaven.test.skip=true

# Stage 2: Run the application on a JRE, with only the jar
FROM eclipse-temurin:25-jre-noble@sha256:d120abd9d8d7dec94520ce974ece62d0e4eed8576eb00bbc84e6128307ab48ef
WORKDIR /app

RUN groupadd --system --gid 10001 appuser \
 && useradd --system --uid 10001 --gid 10001 --no-create-home appuser

# The jar is matched by pattern so a version bump does not break the build
COPY --from=backend-build --chown=10001:10001 /build/target/*.jar app.jar

USER 10001:10001

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 CMD ["/bin/bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /actuator/health/readiness HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n' >&3 && grep -q '\"status\":\"UP\"' <&3"]

ENV JAVA_OPTS="-XX:MaxRAMPercentage=60.0"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
