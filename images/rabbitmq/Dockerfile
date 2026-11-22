# checkov:skip=CKV_DOCKER_2: the broker's probes are declared on its container app, which ignores HEALTHCHECK
FROM rabbitmq:4-alpine@sha256:3dbd31cb9e4d45dac44de7b73cc45b848028494e98bcab71477386cbd4dc7f56

# The workflow passes the date, or the registry cache replays an older upgrade and the rebuild fixes nothing.
ARG APK_UPGRADE_DATE
RUN apk upgrade --no-cache
USER 100:101
