# Stage 1: Build frontend
FROM node:26.3.0-alpine3.22@sha256:c7932b9e5e337b0e733d6e16abc1b0e104759e8b05e59ed56586cce967d26dfe AS frontend-build
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

ENV JAVA_OPTS="-XX:MaxRAMPercentage=60.0"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
