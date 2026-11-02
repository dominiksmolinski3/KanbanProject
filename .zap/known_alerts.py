import re
from urllib.parse import urlsplit

CLIENT_ERRORS = {'400', '401', '403', '404', '405', '409', '415'}
CREDENTIAL_ROUTES = re.compile(r'/api/auth/(login|verify|reset-password)|/api/users/[0-9]+/email-change')

KNOWN = {
    '100000-1': lambda path, alert: alert.get('evidence') in CLIENT_ERRORS,
    '100001': lambda path, alert: not path.startswith('/api/'),
    '10104': lambda path, alert: path == '/api/auth/verify',
    '10111': lambda path, alert: CREDENTIAL_ROUTES.fullmatch(path) is not None,
    '40014-2': lambda path, alert: path.startswith('/api/'),
    '40018': lambda path, alert: path == '/api/auth/verify' and alert.get('param') == 'verificationCode',
}

PAGE = 5000


def is_known(alert):
    matches = KNOWN.get(alert.get('alertRef'))
    return matches is not None and matches(urlsplit(alert.get('url', '')).path, alert)


def zap_get_alerts(zap, baseurl, ignore_scan_rules, out_of_scope_dict):
    known = []
    start = 0
    alerts = zap.core.alerts(baseurl=baseurl, start=start, count=PAGE)
    while alerts:
        known += [alert for alert in alerts if is_known(alert)]
        start += PAGE
        alerts = zap.core.alerts(baseurl=baseurl, start=start, count=PAGE)

    for alert in known:
        zap.alert.delete_alert(alert['id'])

    counts = {ref: 0 for ref in KNOWN}
    for alert in known:
        counts[alert['alertRef']] += 1
    for ref, count in counts.items():
        print(f'Known alert {ref}: {count} dropped')
