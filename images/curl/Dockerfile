# checkov:skip=CKV_DOCKER_2: a one-shot job container; Container Apps jobs never read HEALTHCHECK
FROM curlimages/curl:8.23.0@sha256:3fb7e13e5d179a845c97d78204f3f1064d1907037f26a3ebf91710571151fd93

USER 0
# The workflow passes the date, or the registry cache replays an older upgrade and the rebuild fixes nothing.
ARG APK_UPGRADE_DATE
RUN apk upgrade --no-cache
USER 100:101
