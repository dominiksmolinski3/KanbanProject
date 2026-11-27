FROM dhi.io/node:26-dev@sha256:efd29755a80e4753b972c58d0adeb3d45874c45d200d9ab8a17acf9dbb73620a AS build
WORKDIR /frontend
COPY package*.json ./
RUN npm ci
COPY . ./
ARG VITE_RECAPTCHA_SITE_KEY
ENV VITE_RECAPTCHA_SITE_KEY=${VITE_RECAPTCHA_SITE_KEY}
RUN npm run build

FROM nginx:1.31-alpine@sha256:7fc0fb4af7bf006aeb31aea88069976060f8f0a0a86b667126742cc17f4e31ae

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
