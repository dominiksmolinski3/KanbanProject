import React, { useCallback, useEffect, useState } from 'react';
import { toast } from 'react-toastify';
import { useTranslation } from 'react-i18next';
import { authService } from '../services/authService';
import { getSessionId } from '../services/session';
import { useAuth } from '../context/AuthContext';
import '../styles/components/Devices.css';

// Stand-ins drawn under a blur when the server withholds the real values; never real data.
const REDACTED_AGENT = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/140.0';
const REDACTED_ADDRESS = '000.000.000.000';

function Redacted({ placeholder, label }) {
  return (
    <span className="device-redacted" title={label}>
      <span className="device-redacted-text" aria-hidden="true">{placeholder}</span>
      <span className="device-redacted-label">{label}</span>
    </span>
  );
}

function Devices() {
  const [sessions, setSessions] = useState([]);
  const [isLoading, setIsLoading] = useState(true);
  const [endingId, setEndingId] = useState(null);
  const { t, i18n } = useTranslation();
  const { logout } = useAuth();

  const currentSessionId = getSessionId();

  const isCurrentSession = (session) => String(session.id) === String(currentSessionId);

  const load = useCallback(async () => {
    setIsLoading(true);
    try {
      setSessions(await authService.listDevices());
    } catch (error) {
      console.error('Error:', error);
      toast.error(t('devices.messages.loadError'));
    } finally {
      setIsLoading(false);
    }
  }, [t]);

  useEffect(() => {
    load();
  }, [load]);

  const endSession = async (session) => {
    const isCurrent = isCurrentSession(session);
    if (!window.confirm(isCurrent ? t('devices.messages.endCurrentConfirm') : t('devices.messages.endConfirm'))) {
      return;
    }

    setEndingId(session.id);
    try {
      await authService.endDevice(session.id);
      if (isCurrent) {
        await logout();
        window.location.href = '/';
        return;
      }
      toast.success(t('devices.messages.ended'));
      await load();
    } catch (error) {
      console.error('Error:', error);
      toast.error(t('devices.messages.endError'));
    } finally {
      setEndingId(null);
    }
  };

  const formatMoment = (value) => {
    if (!value) return '—';
    const moment = new Date(value);
    return Number.isNaN(moment.getTime()) ? '—' : moment.toLocaleString(i18n.language);
  };

  return (
    <div className="container">
      <h1>{t('devices.title')}</h1>
      <p className="devices-intro">{t('devices.intro')}</p>
      {sessions.some((session) => session.redacted) && (
        <p className="devices-demo-notice">{t('devices.demoNotice')}</p>
      )}

      <div className="devices-container">
        <div className="devices-header">
          <span>{t('devices.fields.device')}</span>
          <span>{t('devices.fields.address')}</span>
          <span>{t('devices.fields.signedIn')}</span>
          <span>{t('devices.fields.lastSeen')}</span>
          <span>{t('devices.fields.actions')}</span>
        </div>

        <div className="devices-list">
          {isLoading && <div className="devices-empty">{t('devices.messages.loading')}</div>}

          {!isLoading && sessions.length === 0 && (
            <div className="devices-empty">{t('devices.messages.none')}</div>
          )}

          {!isLoading && sessions.map((session) => (
            <div
              key={session.id}
              className={`device-item${isCurrentSession(session) ? ' device-current' : ''}`}
              data-session-id={session.id}
            >
              <span className="device-agent">
                {session.redacted
                  ? <Redacted placeholder={REDACTED_AGENT} label={t('devices.fields.hidden')} />
                  : session.userAgent || t('devices.fields.unknownDevice')}
                {isCurrentSession(session) && (
                  <span className="device-badge">{t('devices.fields.thisDevice')}</span>
                )}
              </span>
              <span className="device-address">
                {session.redacted
                  ? <Redacted placeholder={REDACTED_ADDRESS} label={t('devices.fields.hidden')} />
                  : session.ipAddress || '—'}
              </span>
              <span className="device-moment">{formatMoment(session.signedInAt)}</span>
              <span className="device-moment">{formatMoment(session.lastSeenAt)}</span>
              <span className="device-actions">
                <button
                  className="device-end-btn"
                  disabled={endingId === session.id}
                  onClick={() => endSession(session)}
                >
                  {t('devices.buttons.end')}
                </button>
              </span>
            </div>
          ))}
        </div>
      </div>

      <div className="navigation">
        <a href="/board" className="back-btn">{t('devices.buttons.back')}</a>
      </div>
    </div>
  );
}

export default Devices;
