import React, { useCallback, useEffect, useState } from 'react';
import { toast } from 'react-toastify';
import { useTranslation } from 'react-i18next';
import { authService } from '../services/authService';
import { getSessionId } from '../services/session';
import { useAuth } from '../context/AuthContext';
import '../styles/components/Devices.css';

/**
 * Every session the account can still use, and a button that ends one.
 *
 * The point of the screen is the gap the refresh-token work left: logging out ends the session you
 * are holding, and changing a password ends all of them, so the case in between — a laptop you no
 * longer have, and a session you would rather keep — had no answer that did not sign you out
 * everywhere.
 *
 * "This device" is decided here rather than by the server. The id came back with the session and is
 * replaced on every rotation, so `getSessionId()` is the current answer even when another tab did
 * the renewing; marking it server-side would mean the access token carrying its chain and a lookup
 * on every request, for a fact the client already has.
 */
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

  /**
   * Ending the session you are sitting in is allowed, and it is a sign-out rather than a row
   * disappearing from a list. Refusing it would be the wrong call — it is exactly what somebody
   * does when they have just realised they are on a machine they should not stay signed in to.
   */
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
                {session.userAgent || t('devices.fields.unknownDevice')}
                {isCurrentSession(session) && (
                  <span className="device-badge">{t('devices.fields.thisDevice')}</span>
                )}
              </span>
              <span className="device-address">{session.ipAddress || '—'}</span>
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
