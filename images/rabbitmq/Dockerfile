# checkov:skip=CKV_DOCKER_2: the broker's probes are declared on its container app, which ignores HEALTHCHECK
# checkov:skip=CKV_DOCKER_3: upstream starts as root and its entrypoint drops to rabbitmq
FROM rabbitmq:4-alpine@sha256:8425b8435b0203e87292cf1a4a7e5f8108c610bbd8217f8cc952f43628b24987

# The workflow passes the date, or the registry cache replays an older upgrade and the rebuild fixes nothing.
ARG APK_UPGRADE_DATE
RUN apk upgrade --no-cache
