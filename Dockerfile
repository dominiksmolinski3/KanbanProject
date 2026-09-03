# Stage 1: Build frontend
FROM dhi.io/node:26-dev@sha256:76e72cc05096a00fa7a0b6797b13f47bb01df8655bebd4172562fcc5c52913c9 AS frontend-build
WORKDIR /frontend
COPY frontend/package*.json ./
RUN npm ci --legacy-peer-deps
COPY frontend/ ./
ARG VITE_RECAPTCHA_SITE_KEY
ENV VITE_RECAPTCHA_SITE_KEY=${VITE_RECAPTCHA_SITE_KEY}
RUN npm run build

# Stage 2: Build the backend jar with the frontend bundled in
FROM eclipse-temurin:25-jdk-noble@sha256:534968c051301957beae735e7ba1db54d99ddecf08746d3b9d4f318cc132dbc3 AS backend-build
WORKDIR /build

# Copy the build files first so the wrapper layer survives source-only changes
COPY backend/pom.xml ./
COPY backend/mvnw ./
COPY backend/.mvn ./.mvn/
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw

# Copy the backend source code, then the frontend build output on top of it
COPY backend/src ./src/
COPY --from=frontend-build /frontend/dist/ ./src/main/resources/static/

RUN ./mvnw package -Dmaven.test.skip=true

# Stage 3: Run the application on a JRE, with only the jar
FROM eclipse-temurin:25-jre-noble@sha256:b4c93a50fc67612798db73d68ca3b0ee4ebdd51736e59cca370e689b9797037e
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
