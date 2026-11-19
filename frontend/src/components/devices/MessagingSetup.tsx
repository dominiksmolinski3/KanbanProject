import { useMemo, useState, type FormEvent } from 'react';
import { toast } from 'react-toastify';
import { useTranslation } from 'react-i18next';
import Modal, { ModalTitle } from '../ui/Modal';
import Icon from '../Icon';
import { generateRecoveryKey, recoveryKeyConfirmed } from '../../crypto/recoveryKey';
import { isPin } from '../../crypto/pin';
import { useAsyncAction } from '../../hooks/useAsyncAction';
import PinFields from './PinFields';
import RecoveryKeyFields from './RecoveryKeyFields';
import { setUpMessaging } from './messagingFlows';
import '../../styles/components/Forms.css';

type Register = (register: (userId: number, sessionId: number) => Promise<unknown>) => Promise<void>;

interface MessagingSetupProps {
  mode: 'create' | 'replace';
  pinAvailable: boolean;
  onRegister: Register;
  onClose: () => void;
}

export default function MessagingSetup({ mode, pinAvailable, onRegister, onClose }: MessagingSetupProps) {
  const { t } = useTranslation();
  const [step, setStep] = useState<'intro' | 'pin' | 'key'>('intro');
  const [pin, setPin] = useState('');
  const [repeat, setRepeat] = useState('');
  const [wantRecovery, setWantRecovery] = useState(false);
  const [saved, setSaved] = useState(false);
  const [check, setCheck] = useState('');
  const recoveryKey = useMemo(() => generateRecoveryKey(), []);
  const { pending, error, setError, run } = useAsyncAction();
  const withRecovery = wantRecovery || !pinAvailable;

  const finish = async () => {
    const done = await run(() => onRegister((userId, sessionId) => setUpMessaging({
      userId,
      sessionId,
      pin: pinAvailable ? pin : null,
      recoveryKey: withRecovery ? recoveryKey : null,
      replace: mode === 'replace',
    })), t('devices.messaging.errors.generic'));
    if (done) {
      toast.success(t('devices.messaging.setup.done'));
      onClose();
    }
  };

  const choosePin = async (event: FormEvent) => {
    event.preventDefault();
    if (!isPin(pin)) {
      setError(t('devices.messaging.pin.invalid'));
      return;
    }
    if (pin !== repeat) {
      setError(t('devices.messaging.pin.mismatch'));
      return;
    }
    setError(null);
    if (withRecovery) {
      setStep('key');
    } else {
      await finish();
    }
  };

  const saveKey = async (event: FormEvent) => {
    event.preventDefault();
    if (recoveryKeyConfirmed(recoveryKey, saved, check)) {
      await finish();
    }
  };

  return (
    <Modal onClose={pending ? () => undefined : onClose} overlayClassName="modal-overlay" contentClassName="form-modal messaging-setup" busy={pending}>
      {step === 'intro' && (
        <div className="form-container messaging-setup-step">
          <span className="messaging-setup-icon" aria-hidden="true"><Icon name="lock" /></span>
          <ModalTitle className="messaging-setup-title">
            {mode === 'create' ? t('devices.messaging.setup.introTitle') : t('devices.messaging.reset.title')}
          </ModalTitle>
          {mode === 'create' ? (
            <>
              <p className="messaging-muted">{t('devices.messaging.setup.introBody')}</p>
              <ul className="messaging-points">
                <li>{t('devices.messaging.setup.pointBrowser')}</li>
                <li>{t(pinAvailable ? 'devices.messaging.setup.pointPin' : 'devices.messaging.setup.pointRecovery')}</li>
                <li>{t('devices.messaging.setup.pointEverywhere')}</li>
              </ul>
            </>
          ) : (
            <p className="messaging-muted">{t('devices.messaging.reset.body')}</p>
          )}
          <div className="messaging-actions">
            <button
              type="button"
              className={`btn ${mode === 'create' ? 'btn-primary' : 'btn-danger'}`}
              onClick={() => setStep(pinAvailable ? 'pin' : 'key')}
            >
              {mode === 'create' ? t('devices.messaging.buttons.setUp') : t('devices.messaging.buttons.startOver')}
            </button>
            <button type="button" className="btn btn-secondary" onClick={onClose}>
              {t('devices.messaging.buttons.cancel')}
            </button>
          </div>
        </div>
      )}

      {step === 'pin' && (
        <form className="form-container messaging-setup-step" onSubmit={choosePin}>
          <ModalTitle className="messaging-setup-title">{t('devices.messaging.pin.title')}</ModalTitle>
          <p className="messaging-muted">{t('devices.messaging.pin.body')}</p>
          <PinFields pin={pin} repeat={repeat} onPin={setPin} onRepeat={setRepeat} />
          <p className="messaging-caption">{t('devices.messaging.pin.limits')}</p>
          <label className="messaging-check">
            <input type="checkbox" checked={wantRecovery} onChange={(event) => setWantRecovery(event.target.checked)} />
            <span>
              {t('devices.messaging.pin.addRecovery')}
              <span className="messaging-caption messaging-check-hint">{t('devices.messaging.pin.addRecoveryHint')}</span>
            </span>
          </label>
          {error && <p className="messaging-error" role="alert">{error}</p>}
          <button type="submit" className="btn btn-primary" disabled={pending}>
            {pending
              ? t('devices.messaging.setup.finishing')
              : t(wantRecovery ? 'devices.messaging.buttons.continue' : 'devices.messaging.setup.finish')}
          </button>
        </form>
      )}

      {step === 'key' && (
        <form className="form-container messaging-setup-step" onSubmit={saveKey}>
          <ModalTitle className="messaging-setup-title">{t('devices.messaging.setup.keyTitle')}</ModalTitle>
          <RecoveryKeyFields recoveryKey={recoveryKey} saved={saved} check={check} onSaved={setSaved} onCheck={setCheck} />
          {error && <p className="messaging-error" role="alert">{error}</p>}
          <button type="submit" className="btn btn-primary" disabled={!recoveryKeyConfirmed(recoveryKey, saved, check) || pending}>
            {pending ? t('devices.messaging.setup.finishing') : t('devices.messaging.setup.finish')}
          </button>
          {!pinAvailable && <p className="messaging-caption">{t('devices.messaging.setup.cannotSkip')}</p>}
        </form>
      )}
    </Modal>
  );
}
