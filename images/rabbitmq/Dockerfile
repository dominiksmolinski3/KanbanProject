# checkov:skip=CKV_DOCKER_2: the broker's probes are declared on its container app, which ignores HEALTHCHECK
FROM rabbitmq:4-alpine@sha256:bba4266e0129b9e5c4b5eb985d8e488c77689eaf73399b34b5d176eb8a5d29e7

# The workflow passes the date, or the registry cache replays an older upgrade and the rebuild fixes nothing.
ARG APK_UPGRADE_DATE
RUN apk upgrade --no-cache
USER 100:101
