import json
import re
from urllib.parse import urlsplit

REPORT = '/zap/wrk/report_json.json'

CLIENT_ERRORS = {'400', '401', '403', '404', '405', '409', '415'}
CREDENTIAL_ROUTES = re.compile(r'/api/auth/(login|verify|reset-password)|/api/users/[0-9]+/email-change')

KNOWN = {
    '100000-1': lambda path, instance: instance.get('evidence') in CLIENT_ERRORS,
    '100001': lambda path, instance: not path.startswith('/api/'),
    '10104': lambda path, instance: instance.get('method') != 'GET',
    '10111': lambda path, instance: CREDENTIAL_ROUTES.fullmatch(path) is not None,
    '40014-2': lambda path, instance: path.startswith('/api/'),
    '40018': lambda path, instance: path == '/api/auth/verify' and instance.get('param') == 'verificationCode',
}


def is_known(alert, instance):
    matches = KNOWN.get(alert.get('alertRef'))
    return matches is not None and matches(urlsplit(instance.get('uri', '')).path, instance)


def drop_known(report):
    dropped = {ref: 0 for ref in KNOWN}
    for site in report.get('site', []):
        kept_alerts = []
        for alert in site.get('alerts', []):
            kept = [instance for instance in alert['instances'] if not is_known(alert, instance)]
            if alert.get('alertRef') in dropped:
                dropped[alert['alertRef']] += len(alert['instances']) - len(kept)
            if kept:
                alert['instances'] = kept
                alert['count'] = str(len(kept))
                kept_alerts.append(alert)
        site['alerts'] = kept_alerts
    return dropped


def zap_pre_shutdown(zap):
    with open(REPORT, encoding='utf-8') as f:
        report = json.load(f)
    dropped = drop_known(report)
    with open(REPORT, 'w', encoding='utf-8') as f:
        json.dump(report, f)
    for ref, count in dropped.items():
        print(f'Known alert {ref}: {count} dropped')
