# checkov:skip=CKV_DOCKER_2: a one-shot job container; Container Apps jobs never read HEALTHCHECK
FROM postgres:17-alpine@sha256:f9cf3e1f0066f0eb0c48707d864f0ac5941e9ff454a072077d97a4d278d07d73

# The workflow passes the date, or the registry cache replays an older upgrade and the rebuild fixes nothing.
ARG APK_UPGRADE_DATE
# The jobs replace the entrypoint, so its gosu never drops root; the image starts as postgres instead.
RUN apk upgrade --no-cache \
 && rm /usr/local/bin/gosu
USER 70:70
