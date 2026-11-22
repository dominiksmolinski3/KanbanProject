# checkov:skip=CKV_DOCKER_2: a one-shot job container; Container Apps jobs never read HEALTHCHECK
FROM postgres:17-alpine@sha256:d3244e8ce3f165ee3c93d9c4a4b1d0fe48b3a89e2287b717fdcbbde92d405065

# The workflow passes the date, or the registry cache replays an older upgrade and the rebuild fixes nothing.
ARG APK_UPGRADE_DATE
# The jobs replace the entrypoint, so its gosu never drops root; the image starts as postgres instead.
RUN apk upgrade --no-cache \
 && rm /usr/local/bin/gosu
USER 70:70
