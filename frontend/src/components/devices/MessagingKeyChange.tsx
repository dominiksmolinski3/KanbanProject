import { useMemo, useState, type FormEvent } from 'react';
import { toast } from 'react-toastify';
import { useTranslation } from 'react-i18next';
import Modal, { ModalTitle } from '../ui/Modal';
import { useAuth } from '../../context/AuthContext';
import { generateRecoveryKey, recoveryKeyConfirmed } from '../../crypto/recoveryKey';
import { isPin } from '../../crypto/pin';
import { useAsyncAction } from '../../hooks/useAsyncAction';
import PinFields from './PinFields';
import RecoveryKeyFields from './RecoveryKeyFields';
import { addRecoveryKey, savePin } from './messagingFlows';
import '../../styles/components/Forms.css';

interface MessagingKeyChangeProps {
  kind: 'pin' | 'recovery';
  onDone: () => Promise<void>;
  onClose: () => void;
}

export default function MessagingKeyChange({ kind, onDone, onClose }: MessagingKeyChangeProps) {
  const { t } = useTranslation();
  const { user } = useAuth();
  const [pin, setPin] = useState('');
  const [repeat, setRepeat] = useState('');
  const [saved, setSaved] = useState(false);
  const [check, setCheck] = useState('');
  const recoveryKey = useMemo(() => generateRecoveryKey(), []);
  const { pending, error, setError, run } = useAsyncAction();

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (kind === 'pin') {
      if (!isPin(pin)) {
        setError(t('devices.messaging.pin.invalid'));
        return;
      }
      if (pin !== repeat) {
        setError(t('devices.messaging.pin.mismatch'));
        return;
      }
    } else if (!recoveryKeyConfirmed(recoveryKey, saved, check)) {
      return;
    }
    const done = await run(async () => {
      if (kind === 'pin') {
        await savePin(user?.id as number, pin);
      } else {
        await addRecoveryKey(recoveryKey);
      }
      await onDone();
    }, t('devices.messaging.errors.generic'));
    if (done) {
      toast.success(t(kind === 'pin' ? 'devices.messaging.changePin.done' : 'devices.messaging.addRecovery.done'));
      onClose();
    }
  };

  return (
    <Modal onClose={pending ? () => undefined : onClose} overlayClassName="modal-overlay" contentClassName="form-modal messaging-setup" busy={pending}>
      <form className="form-container messaging-setup-step" onSubmit={submit}>
        <ModalTitle className="messaging-setup-title">
          {t(kind === 'pin' ? 'devices.messaging.changePin.title' : 'devices.messaging.setup.keyTitle')}
        </ModalTitle>
        {kind === 'pin' ? (
          <>
            <p className="messaging-muted">{t('devices.messaging.pin.body')}</p>
            <PinFields pin={pin} repeat={repeat} onPin={setPin} onRepeat={setRepeat} />
          </>
        ) : (
          <RecoveryKeyFields recoveryKey={recoveryKey} saved={saved} check={check} onSaved={setSaved} onCheck={setCheck} />
        )}
        {error && <p className="messaging-error" role="alert">{error}</p>}
        <div className="messaging-actions">
          <button
            type="submit"
            className="btn btn-primary"
            disabled={pending || (kind === 'recovery' && !recoveryKeyConfirmed(recoveryKey, saved, check))}
          >
            {pending ? t('devices.messaging.setup.finishing') : t('devices.messaging.buttons.save')}
          </button>
          <button type="button" className="btn btn-secondary" onClick={onClose} disabled={pending}>
            {t('devices.messaging.buttons.cancel')}
          </button>
        </div>
      </form>
    </Modal>
  );
}
