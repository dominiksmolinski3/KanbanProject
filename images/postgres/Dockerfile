# checkov:skip=CKV_DOCKER_2: a one-shot job container; Container Apps jobs never read HEALTHCHECK
FROM postgres:18-alpine@sha256:0115267ab5a509022df9319c77be365cef335adb71556c75f60f5360ee247e71

# The workflow passes the date, or the registry cache replays an older upgrade and the rebuild fixes nothing.
ARG APK_UPGRADE_DATE
# The jobs replace the entrypoint, so its gosu never drops root; the image starts as postgres instead.
RUN apk upgrade --no-cache \
 && rm /usr/local/bin/gosu
USER 70:70
