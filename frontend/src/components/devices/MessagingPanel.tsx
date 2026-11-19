import { useState } from 'react';
import { toast } from 'react-toastify';
import { useTranslation } from 'react-i18next';
import Icon from '../Icon';
import { keyFingerprint } from '../../crypto/fingerprint';
import type { MessagingDevice } from '../../services/cryptoApi';
import type { useMessagingKeys } from './useMessagingKeys';
import MessagingSetup from './MessagingSetup';
import MessagingUnlock from './MessagingUnlock';
import MessagingKeyChange from './MessagingKeyChange';

type Messaging = ReturnType<typeof useMessagingKeys>;

type Dialog = 'create' | 'replace' | 'pin' | 'recovery' | 'changePin' | 'addRecovery' | null;

export default function MessagingPanel({ messaging }: { messaging: Messaging }) {
  const { t, i18n } = useTranslation();
  const [dialog, setDialog] = useState<Dialog>(null);
  const { state, removed, thisDevice, identity, pin, signedOutDevices } = messaging;

  if (state === 'off') {
    return null;
  }

  const pinAvailable = pin?.available === true;
  const pinUsable = pinAvailable && pin?.set === true && pin.attemptsLeft > 0;
  const pinLocked = pinAvailable && pin?.set === true && pin.attemptsLeft === 0;
  const close = () => setDialog(null);

  const forget = async (device: MessagingDevice, confirmKey: string, doneKey: string) => {
    if (!window.confirm(t(confirmKey))) {
      return;
    }
    try {
      await messaging.forget(device);
      toast.success(t(doneKey));
    } catch (error) {
      console.error('Error:', error);
      toast.error(t('devices.messaging.errors.generic'));
    }
  };

  const lastUsed = (device: MessagingDevice) => {
    const moment = new Date(device.lastSeenAt);
    return Number.isNaN(moment.getTime()) ? '—' : moment.toLocaleDateString(i18n.language);
  };

  return (
    <section className="page-panel messaging-panel" aria-labelledby="messaging-panel-title" aria-busy={state === 'loading'}>
      <div className="messaging-panel-head">
        <span className="messaging-panel-icon" aria-hidden="true"><Icon name="lock" /></span>
        <h2 className="page-panel-title" id="messaging-panel-title">{t('devices.messaging.title')}</h2>
      </div>

      {removed && state !== 'ready' && (
        <p className="messaging-notice" role="status">
          <Icon name="warning" size="sm" />
          {t('devices.messaging.removed')}
        </p>
      )}

      {state === 'loading' && <p className="messaging-muted">{t('devices.messaging.loading')}</p>}

      {state === 'notSetUp' && (
        <>
          <p className="messaging-muted">{t('devices.messaging.notSetUp')}</p>
          <div className="messaging-actions">
            <button type="button" className="btn btn-primary" onClick={() => setDialog('create')}>
              {t('devices.messaging.buttons.setUp')}
            </button>
          </div>
        </>
      )}

      {state === 'elsewhere' && (
        <>
          <p className="messaging-muted">{t('devices.messaging.elsewhere')}</p>
          {pinLocked && (
            <p className="messaging-notice" role="status">
              <Icon name="warning" size="sm" />
              {t('devices.messaging.errors.pinLocked')}
            </p>
          )}
          <div className="messaging-actions">
            {pinUsable && (
              <button type="button" className="btn btn-primary" onClick={() => setDialog('pin')}>
                {t('devices.messaging.buttons.enterPin')}
              </button>
            )}
            {identity?.recoveryKey && (
              <button
                type="button"
                className={`btn ${pinUsable ? 'btn-secondary' : 'btn-primary'}`}
                onClick={() => setDialog('recovery')}
              >
                {t('devices.messaging.buttons.useRecoveryKey')}
              </button>
            )}
            <button type="button" className="btn btn-danger-quiet" onClick={() => setDialog('replace')}>
              {t('devices.messaging.buttons.startOver')}
            </button>
          </div>
        </>
      )}

      {state === 'ready' && thisDevice && (
        <>
          <p className="messaging-ready">
            <Icon name="check" size="sm" />
            {t('devices.messaging.ready')}
          </p>
          <p className="messaging-key">
            <span className="device-field-label">{t('devices.messaging.thisKey')}</span>
            <code dir="ltr">{keyFingerprint(thisDevice.signatureKey)}</code>
          </p>
          <p className="messaging-caption">{t('devices.messaging.keepsKey')}</p>
          {!identity?.recoveryKey && <p className="messaging-caption">{t('devices.messaging.noRecoveryKey')}</p>}
          <div className="messaging-actions">
            {pinAvailable && (
              <button type="button" className="btn btn-secondary btn-sm" onClick={() => setDialog('changePin')}>
                {t(pin?.set ? 'devices.messaging.buttons.changePin' : 'devices.messaging.buttons.setPin')}
              </button>
            )}
            {!identity?.recoveryKey && (
              <button type="button" className="btn btn-secondary btn-sm" onClick={() => setDialog('addRecovery')}>
                {t('devices.messaging.buttons.addRecoveryKey')}
              </button>
            )}
            <button
              type="button"
              className="btn btn-danger-quiet btn-sm"
              onClick={() => forget(thisDevice, 'devices.messaging.forgetConfirm', 'devices.messaging.forgotten')}
            >
              {t('devices.messaging.buttons.forget')}
            </button>
          </div>
        </>
      )}

      {state !== 'loading' && signedOutDevices.length > 0 && (
        <div className="messaging-signed-out">
          <h3 className="messaging-subtitle">{t('devices.messaging.signedOut.title')}</h3>
          <ul className="messaging-device-list">
            {signedOutDevices.map((device) => (
              <li key={device.id} className="messaging-device">
                <span className="messaging-device-main">
                  <span className="messaging-device-label">{device.label || t('devices.fields.unknownDevice')}</span>
                  <span className="messaging-caption">
                    {t('devices.messaging.signedOut.lastUsed', { date: lastUsed(device) })}
                    {' · '}
                    <code dir="ltr">{keyFingerprint(device.signatureKey)}</code>
                  </span>
                </span>
                <button
                  type="button"
                  className="btn btn-danger-quiet btn-sm"
                  onClick={() => forget(device, 'devices.messaging.signedOut.removeConfirm', 'devices.messaging.signedOut.removed')}
                >
                  {t('devices.messaging.buttons.remove')}
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}

      {(dialog === 'create' || dialog === 'replace') && (
        <MessagingSetup mode={dialog} pinAvailable={pinAvailable} onRegister={messaging.registerBrowser} onClose={close} />
      )}
      {(dialog === 'pin' || dialog === 'recovery') && (
        <MessagingUnlock
          mode={dialog}
          attemptsLeft={pin?.attemptsLeft ?? 0}
          onRegister={messaging.registerBrowser}
          onFailedAttempt={messaging.refresh}
          onClose={close}
        />
      )}
      {(dialog === 'changePin' || dialog === 'addRecovery') && (
        <MessagingKeyChange kind={dialog === 'changePin' ? 'pin' : 'recovery'} onDone={messaging.refresh} onClose={close} />
      )}
    </section>
  );
}
