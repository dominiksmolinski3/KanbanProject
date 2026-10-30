FROM dhi.io/node:26-dev@sha256:4340524d4beca6e68610d8d1ee49a0c68f5194ff6ef904b09e8d555aaf9a160d AS build
WORKDIR /frontend
COPY package*.json ./
RUN npm ci
COPY . ./
ARG VITE_RECAPTCHA_SITE_KEY
ENV VITE_RECAPTCHA_SITE_KEY=${VITE_RECAPTCHA_SITE_KEY}
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.31-alpine@sha256:b5047b9b76845eac124358373c6852377c52e4b05834a47647e955dd91cb8f1e

# Numeric UID/GID on both lines: hadolint's DL3066 flags a name a host system may not resolve.
USER 0
# CD passes the date, or the registry cache replays yesterday's upgrade and the sweep fixes nothing.
ARG APK_UPGRADE_DATE
RUN apk upgrade --no-cache
USER 101

ENV NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY nginx/security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY --from=build /frontend/dist/ /usr/share/nginx/html/

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD ["wget", "--quiet", "--tries=1", "--spider", "http://127.0.0.1:8080/healthz"]
