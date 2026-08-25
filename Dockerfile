# Stage 1: Build frontend
FROM node:24.6.0-alpine3.22 AS frontend-build
WORKDIR /frontend
COPY frontend/package*.json ./
RUN npm ci --legacy-peer-deps
COPY frontend/ ./
ARG VITE_RECAPTCHA_SITE_KEY
ENV VITE_RECAPTCHA_SITE_KEY=${VITE_RECAPTCHA_SITE_KEY}
RUN npm run build

# Stage 2: Build the backend jar with the frontend bundled in
FROM eclipse-temurin:23-jdk AS backend-build
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
FROM eclipse-temurin:23-jre
WORKDIR /app

# The jar is matched by pattern so a version bump does not break the build
COPY --from=backend-build /build/target/*.jar app.jar

EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
