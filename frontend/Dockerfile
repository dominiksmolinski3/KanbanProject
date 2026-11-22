FROM dhi.io/node:26-dev@sha256:7ddf2a15a09294322b6508b4dbc97be8cbd792ba3d76559d338b4bbe84baf8b5 AS build
WORKDIR /frontend
COPY package*.json ./
RUN npm ci
COPY . ./
ARG VITE_RECAPTCHA_SITE_KEY
ENV VITE_RECAPTCHA_SITE_KEY=${VITE_RECAPTCHA_SITE_KEY}
RUN npm run build

FROM nginx:1.31-alpine@sha256:fb4658584bd9a916ffb6f6e365f97c25cfdb2923801f551ea08356e695f14ff7

# CD passes the date, or the registry cache replays yesterday's upgrade and the sweep fixes nothing.
ARG APK_UPGRADE_DATE
# The entrypoint renders the template into conf.d as uid 101; the stock default.conf would serve port 80.
RUN apk upgrade --no-cache \
    && rm /etc/nginx/conf.d/default.conf \
    && chown 101:101 /etc/nginx/conf.d /var/cache/nginx
# Numeric: hadolint's DL3066 flags a name a host system may not resolve.
USER 101

ENV NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1
COPY nginx/nginx.conf /etc/nginx/nginx.conf
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY nginx/security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY --from=build /frontend/dist/ /usr/share/nginx/html/

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD ["wget", "--quiet", "--tries=1", "--spider", "http://127.0.0.1:8080/healthz"]
