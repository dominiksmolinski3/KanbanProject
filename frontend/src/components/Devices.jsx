import React, { useCallback, useEffect, useState } from 'react';
import { toast } from 'react-toastify';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import Icon from './Icon';
import { authService } from '../services/authService';
import { getSessionId } from '../services/session';
import { useAuth } from '../context/AuthContext';
import '../styles/components/Devices.css';

// Stand-ins drawn under a blur when the server withholds the real values; never real data.
const REDACTED_AGENT = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/140.0';
const REDACTED_ADDRESS = '000.000.000.000';

const MOBILE = /Mobi|Android|iPhone|iPad/i;

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
    <div className="page-shell devices-page">
      <div className="page-head">
        <h1 className="page-title">{t('devices.title')}</h1>
        <p className="page-lede devices-intro">{t('devices.intro')}</p>
      </div>
      {sessions.some((session) => session.redacted) && (
        <p className="devices-demo-notice" role="status">
          <Icon name="eye" size="sm" />
          {t('devices.demoNotice')}
        </p>
      )}

      <section className="page-panel devices-list" aria-label={t('devices.title')}>
        {isLoading && <div className="page-empty devices-empty">{t('devices.messages.loading')}</div>}

        {!isLoading && sessions.length === 0 && (
          <div className="page-empty devices-empty">
            <span className="page-empty-icon"><Icon name="laptop" /></span>
            <p className="page-empty-title">{t('devices.messages.none')}</p>
          </div>
        )}

        {!isLoading && sessions.map((session) => (
          <div
            key={session.id}
            className={`device-item${isCurrentSession(session) ? ' device-current' : ''}`}
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
                {isCurrentSession(session) && (
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
              disabled={endingId === session.id}
              onClick={() => endSession(session)}
            >
              {t('devices.buttons.end')}
            </button>
          </div>
        ))}
      </section>

      <Link to="/account" className="devices-back">
        <Icon name="chevron-left" size="sm" />
        {t('header.account')}
      </Link>
    </div>
  );
}

export default Devices;
