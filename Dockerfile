# Stage 1: Build frontend
FROM node:24.6.0-alpine3.22@sha256:51dbfc749ec3018c7d4bf8b9ee65299ff9a908e38918ce163b0acfcd5dd931d9 AS frontend-build
WORKDIR /frontend
COPY frontend/package*.json ./
RUN npm ci --legacy-peer-deps
COPY frontend/ ./
ARG VITE_RECAPTCHA_SITE_KEY
ENV VITE_RECAPTCHA_SITE_KEY=${VITE_RECAPTCHA_SITE_KEY}
RUN npm run build

# Stage 2: Build the backend jar with the frontend bundled in
FROM eclipse-temurin:21-jdk-noble@sha256:75ce56643243c3db632be2ef259625fb42ee3be1334389659f7a1a61acb78783 AS backend-build
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
FROM eclipse-temurin:21-jre-noble@sha256:96975602e131485862eb8cd32927face8a06d7591a5e865944b634a701d9df72
WORKDIR /app

RUN groupadd --system --gid 10001 appuser \
 && useradd --system --uid 10001 --gid 10001 --no-create-home appuser

# The jar is matched by pattern so a version bump does not break the build
COPY --from=backend-build --chown=10001:10001 /build/target/*.jar app.jar

USER 10001:10001

EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
