FROM dhi.io/node:26-dev@sha256:76e72cc05096a00fa7a0b6797b13f47bb01df8655bebd4172562fcc5c52913c9 AS build
WORKDIR /frontend
COPY package*.json ./
RUN npm ci --legacy-peer-deps
COPY . ./
ARG VITE_RECAPTCHA_SITE_KEY
ENV VITE_RECAPTCHA_SITE_KEY=${VITE_RECAPTCHA_SITE_KEY}
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.29-alpine@sha256:0c79d56aee561a1d81c63f00eee5fb5fe29279560cdc55e91425133104c7fbe6

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
