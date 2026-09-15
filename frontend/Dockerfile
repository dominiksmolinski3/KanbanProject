# Stage 1: Build the bundle
FROM dhi.io/node:26-dev@sha256:76e72cc05096a00fa7a0b6797b13f47bb01df8655bebd4172562fcc5c52913c9 AS build
WORKDIR /frontend
COPY package*.json ./
RUN npm ci --legacy-peer-deps
COPY . ./
# Baked into the bundle at build time, which is why it is an ARG and not an environment variable:
# the site key is fixed when the image is built and cannot be changed by restarting the container.
# Its other half, CAPTCHA_SECRET, is read by the API at startup - set one without the other and the
# check either never renders or is decorative.
ARG VITE_RECAPTCHA_SITE_KEY
ENV VITE_RECAPTCHA_SITE_KEY=${VITE_RECAPTCHA_SITE_KEY}
RUN npm run build

# Stage 2: Serve it, and proxy what the API owns
#
# Unprivileged rather than the stock nginx image: this replaces a jar that ran as UID 10001, and
# dropping to a root nginx would be a regression nothing here checks. The image runs as UID 101 and
# listens on 8080 already, which is the port the ingress and docker-compose both expect.
FROM nginxinc/nginx-unprivileged:1.29-alpine@sha256:0c79d56aee561a1d81c63f00eee5fb5fe29279560cdc55e91425133104c7fbe6

# Bring the base's package set up to Alpine's security branch, then drop back to the image's own
# non-root user.
#
# Not defensive and not optional. The digest pinned above is the newest `1.29-alpine` Docker Hub
# publishes, and its packages are far enough behind Alpine 3.23's security branch that Trivy found
# 35 fixable HIGH findings across eight packages - curl and libcurl alone were 20 of them, in an
# image whose only HTTP client is the busybox wget at the bottom of this file. That failed the CD
# scan gate on the first run after the container split, correctly, and blocked `:latest` on both
# images. This is the fix rather than an exception to it: after the upgrade the same scan reports
# zero.
#
# The tension with pinning a digest is real and is resolved in favour of patching. The digest still
# fixes the starting point and the build stays reproducible in the way that matters - what varies
# between two builds is a set of distro package versions that only ever moves forward. A base image
# nobody upgrades is reproducibly vulnerable.
#
# `dhi.io/nginx` was the other candidate, for consistency with the node stage above, and is
# declined: it has no /docker-entrypoint.d, no envsubst and no wget, so taking it would mean
# hand-writing the templating, the resolver discovery and the healthcheck - redesigning the half of
# this that was verified end to end, to avoid one RUN line.
#
# Numeric on both lines, because hadolint's DL3066 flags a name a host system may not resolve -
# and `backend/Dockerfile` already writes its user as `10001:10001` for the same reason.
USER 0
RUN apk upgrade --no-cache
USER 101

# The stock entrypoint runs envsubst over /etc/nginx/templates/*.template into /etc/nginx/conf.d,
# so the upstream is a deployment fact supplied at start rather than baked into the image - the
# same image runs against dev's internal FQDN and against http://app:8080 in docker-compose. No
# custom entrypoint is needed for either this or the resolver.
ENV NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY nginx/security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY --from=build /frontend/dist/ /usr/share/nginx/html/

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD ["wget", "--quiet", "--tries=1", "--spider", "http://127.0.0.1:8080/healthz"]
