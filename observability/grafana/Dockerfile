FROM grafana/grafana:13.3.1@sha256:0615ec4fbd4072004b7d86b46ded63013a74b7d6eb1119273dd89e8fa25c13cf

USER 0
# CD passes the date, or the registry cache replays yesterday's upgrade and the sweep fixes nothing.
ARG APK_UPGRADE_DATE
RUN apk upgrade --no-cache \
 && find /usr/share/grafana/data/plugins-bundled -mindepth 1 -maxdepth 1 ! -name prometheus ! -name canvas -exec rm -rf {} +
USER 472

COPY --chown=472:0 dashboards.yml /etc/grafana/provisioning/dashboards/kanban.yml
COPY --chown=472:0 dashboards/ /etc/grafana/dashboards/
COPY --chown=472:0 azure/dashboards/ /etc/grafana/dashboards/azure/
COPY --chown=472:0 azure/datasources.yml /etc/grafana/provisioning/datasources/azure.yml

ENV GF_ANALYTICS_REPORTING_ENABLED=false \
    GF_ANALYTICS_CHECK_FOR_UPDATES=false \
    GF_AUTH_ANONYMOUS_ENABLED=false \
    GF_AUTH_DISABLE_LOGIN_FORM=true \
    GF_AUTH_OAUTH_AUTO_LOGIN=true \
    GF_PLUGINS_PREINSTALL_DISABLED=true \
    GF_USERS_ALLOW_SIGN_UP=false

EXPOSE 3000

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD ["wget", "--quiet", "--tries=1", "--spider", "http://127.0.0.1:3000/api/health"]
