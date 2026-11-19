import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import Icon from '../Icon';
import { keyFingerprint } from '../../crypto/fingerprint';
import type { MessagingDevice } from '../../services/cryptoApi';
import type { MessagingState } from './useMessagingKeys';
import MessagingSetup from './MessagingSetup';
import MessagingRecover from './MessagingRecover';

interface MessagingPanelProps {
  state: MessagingState;
  removed: boolean;
  thisDevice: MessagingDevice | undefined;
  onRegister: () => Promise<void>;
}

type Dialog = 'create' | 'replace' | 'recover' | null;

export default function MessagingPanel({ state, removed, thisDevice, onRegister }: MessagingPanelProps) {
  const { t } = useTranslation();
  const [dialog, setDialog] = useState<Dialog>(null);

  if (state === 'off') {
    return null;
  }

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
          <div className="messaging-actions">
            <button type="button" className="btn btn-primary" onClick={() => setDialog('recover')}>
              {t('devices.messaging.buttons.useRecoveryKey')}
            </button>
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
        </>
      )}

      {(dialog === 'create' || dialog === 'replace') && (
        <MessagingSetup mode={dialog} onRegister={onRegister} onClose={() => setDialog(null)} />
      )}
      {dialog === 'recover' && <MessagingRecover onRegister={onRegister} onClose={() => setDialog(null)} />}
    </section>
  );
}
