import { useTranslation } from 'react-i18next';
import type { ActiveDevice } from '../../types/api';
import Icon from '../Icon';

// Stand-ins drawn under a blur when the server withholds the real values; never real data.
const REDACTED_AGENT = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/140.0';
const REDACTED_ADDRESS = '000.000.000.000';

const MOBILE = /Mobi|Android|iPhone|iPad/i;

function Redacted({ placeholder, label }: { placeholder: string; label: string }) {
  return (
    <span className="device-redacted" title={label}>
      <span className="device-redacted-text" aria-hidden="true">{placeholder}</span>
      <span className="device-redacted-label">{label}</span>
    </span>
  );
}

interface DeviceItemProps {
  session: ActiveDevice;
  isCurrent: boolean;
  ending: boolean;
  onEnd: (session: ActiveDevice) => void;
}

export default function DeviceItem({ session, isCurrent, ending, onEnd }: DeviceItemProps) {
  const { t, i18n } = useTranslation();

  const formatMoment = (value: string | null | undefined) => {
    if (!value) return '—';
    const moment = new Date(value);
    return Number.isNaN(moment.getTime()) ? '—' : moment.toLocaleString(i18n.language);
  };

  return (
    <div
      className={`device-item${isCurrent ? ' device-current' : ''}`}
      data-session-id={session.id}
    >
      <span className="device-icon" aria-hidden="true">
        <Icon name={MOBILE.test(session.userAgent || '') ? 'phone' : 'laptop'} />
      </span>
      <div className="device-main">
        <span className="device-agent">
          {session.redacted
            ? <Redacted placeholder={REDACTED_AGENT} label={t('devices.fields.hidden')} />
            : session.userAgent || t('devices.fields.unknownDevice')}
          {isCurrent && (
            <span className="device-badge">{t('devices.fields.thisDevice')}</span>
          )}
        </span>
        <span className="device-meta">
          <span className="device-field">
            <span className="device-field-label">{t('devices.fields.address')}</span>
            <span className="device-address">
              {session.redacted
                ? <Redacted placeholder={REDACTED_ADDRESS} label={t('devices.fields.hidden')} />
                : session.ipAddress || '—'}
            </span>
          </span>
          <span className="device-field">
            <span className="device-field-label">{t('devices.fields.signedIn')}</span>
            <span className="device-moment">{formatMoment(session.signedInAt)}</span>
          </span>
          <span className="device-field">
            <span className="device-field-label">{t('devices.fields.lastSeen')}</span>
            <span className="device-moment">{formatMoment(session.lastSeenAt)}</span>
          </span>
        </span>
      </div>
      <button
        type="button"
        className="btn btn-danger-quiet btn-sm device-end-btn"
        disabled={ending}
        onClick={() => onEnd(session)}
      >
        {t('devices.buttons.end')}
      </button>
    </div>
  );
}
