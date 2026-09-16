# Stage 1: Build the bundle
FROM dhi.io/node:26-dev@sha256:76e72cc05096a00fa7a0b6797b13f47bb01df8655bebd4172562fcc5c52913c9 AS build
WORKDIR /frontend
COPY package*.json ./
RUN npm ci --legacy-peer-deps
COPY . ./
# Baked into the bundle at build time (ARG, not an env var) - fixed once the image is built, and
# can't change by restarting the container. Its server-side half, CAPTCHA_SECRET, is read at API
# startup; set one without the other and the check either never renders or is decorative.
ARG VITE_RECAPTCHA_SITE_KEY
ENV VITE_RECAPTCHA_SITE_KEY=${VITE_RECAPTCHA_SITE_KEY}
RUN npm run build

# Stage 2: Serve it, and proxy what the API owns
#
# Unprivileged rather than the stock nginx image: this replaces a jar that ran as UID 10001, and a
# root nginx would be a regression nothing here checks. Runs as UID 101, listening on 8080 already -
# the port the ingress and docker-compose both expect.
FROM nginxinc/nginx-unprivileged:1.29-alpine@sha256:0c79d56aee561a1d81c63f00eee5fb5fe29279560cdc55e91425133104c7fbe6

# Bring the base's package set up to Alpine's security branch, then drop back to non-root.
#
# Not defensive: the pinned digest is the newest `1.29-alpine` tag, but its packages were far enough
# behind Alpine's security branch that Trivy found 35 fixable HIGH findings (20 in curl/libcurl
# alone, unused here) - which failed the CD scan gate and blocked `:latest` on both images. After the
# upgrade the scan reports zero; the digest still fixes a reproducible starting point, and only
# distro package versions move (forward only) between builds.
#
# `dhi.io/nginx` was considered for consistency with the node stage and declined: it has no
# /docker-entrypoint.d, envsubst or wget, so it would mean hand-writing the templating, resolver
# discovery and healthcheck this image already has verified end to end.
#
# Numeric UID/GID on both lines: hadolint's DL3066 flags a name a host system may not resolve.
USER 0
RUN apk upgrade --no-cache
USER 101

# The stock entrypoint runs envsubst over /etc/nginx/templates/*.template into /etc/nginx/conf.d, so
# the upstream is a deployment fact supplied at start rather than baked into the image - the same
# image runs against dev's internal FQDN and against http://app:8080 in docker-compose.
ENV NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY nginx/security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY --from=build /frontend/dist/ /usr/share/nginx/html/

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD ["wget", "--quiet", "--tries=1", "--spider", "http://127.0.0.1:8080/healthz"]
