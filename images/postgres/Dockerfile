# checkov:skip=CKV_DOCKER_2: a one-shot job container; Container Apps jobs never read HEALTHCHECK
FROM postgres:19-alpine@sha256:cac45fb483caee3668946088a1c0532db89d5285c28dbfeac889b4068296e06f

# The workflow passes the date, or the registry cache replays an older upgrade and the rebuild fixes nothing.
ARG APK_UPGRADE_DATE
# The jobs replace the entrypoint, so its gosu never drops root; the image starts as postgres instead.
RUN apk upgrade --no-cache \
 && rm /usr/local/bin/gosu
USER 70:70
