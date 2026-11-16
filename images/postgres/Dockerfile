# checkov:skip=CKV_DOCKER_2: a one-shot job container; Container Apps jobs never read HEALTHCHECK
# checkov:skip=CKV_DOCKER_3: upstream starts as root and its entrypoint drops to postgres with gosu
FROM postgres:17-alpine@sha256:d3244e8ce3f165ee3c93d9c4a4b1d0fe48b3a89e2287b717fdcbbde92d405065

# The workflow passes the date, or the registry cache replays an older upgrade and the rebuild fixes nothing.
ARG APK_UPGRADE_DATE
RUN apk upgrade --no-cache
