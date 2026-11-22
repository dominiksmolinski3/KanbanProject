# checkov:skip=CKV_DOCKER_2: the broker's probes are declared on its container app, which ignores HEALTHCHECK
FROM rabbitmq:4-alpine@sha256:7b77c3d3a996c7fcca1ca8bf53ce1557ff43c13b8ca2c132df6031781b209ef2

# The workflow passes the date, or the registry cache replays an older upgrade and the rebuild fixes nothing.
ARG APK_UPGRADE_DATE
RUN apk upgrade --no-cache
USER 100:101
