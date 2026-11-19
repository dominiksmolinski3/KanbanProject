import { useState, type FormEvent } from 'react';
import { toast } from 'react-toastify';
import { useTranslation } from 'react-i18next';
import Modal, { ModalTitle } from '../ui/Modal';
import { VaultError } from '../../crypto/client';
import { isPin } from '../../crypto/pin';
import { normalizeRecoveryKey } from '../../crypto/recoveryKey';
import { ApiError } from '../../services/apiError';
import { useAsyncAction } from '../../hooks/useAsyncAction';
import { PinField } from './PinFields';
import { registerThisBrowser, unlockWithPin, unlockWithRecoveryKey } from './messagingFlows';
import '../../styles/components/Forms.css';

type Register = (register: (userId: number, sessionId: number) => Promise<unknown>) => Promise<void>;

interface MessagingUnlockProps {
  mode: 'pin' | 'recovery';
  attemptsLeft: number;
  onRegister: Register;
  onFailedAttempt: () => Promise<void>;
  onClose: () => void;
}

export default function MessagingUnlock({ mode, attemptsLeft, onRegister, onFailedAttempt, onClose }: MessagingUnlockProps) {
  const { t } = useTranslation();
  const [input, setInput] = useState('');
  const [wrongPin, setWrongPin] = useState(false);
  const { pending, error, setError, run } = useAsyncAction();

  const unlock = async (userId: number, sessionId: number) => {
    if (mode === 'pin') {
      await unlockWithPin(userId, input);
    } else {
      await unlockWithRecoveryKey(normalizeRecoveryKey(input) as string);
    }
    await registerThisBrowser(userId, sessionId);
  };

  const explain = (failure: unknown): never => {
    if (failure instanceof ApiError && failure.code === 'PIN_INCORRECT') {
      setWrongPin(true);
      throw new Error(t('devices.messaging.errors.wrongPin'), { cause: failure });
    }
    if (failure instanceof ApiError && failure.code === 'PIN_LOCKED') {
      throw new Error(t('devices.messaging.errors.pinLocked'), { cause: failure });
    }
    if (failure instanceof VaultError && failure.name === 'RecoveryKeyMismatchError') {
      throw new Error(t('devices.messaging.errors.wrongKey'), { cause: failure });
    }
    throw failure;
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (mode === 'pin' && !isPin(input)) {
      setError(t('devices.messaging.pin.invalid'));
      return;
    }
    if (mode === 'recovery' && !normalizeRecoveryKey(input)) {
      setError(t('devices.messaging.errors.malformedKey'));
      return;
    }
    setWrongPin(false);
    const done = await run(async () => {
      try {
        await onRegister(unlock);
      } catch (failure) {
        if (mode === 'pin') {
          await onFailedAttempt();
        }
        explain(failure);
      }
    }, t('devices.messaging.errors.generic'));
    if (done) {
      toast.success(t('devices.messaging.setup.done'));
      onClose();
    }
  };

  return (
    <Modal onClose={pending ? () => undefined : onClose} overlayClassName="modal-overlay" contentClassName="form-modal messaging-setup" busy={pending}>
      <form className="form-container messaging-setup-step" onSubmit={submit}>
        <ModalTitle className="messaging-setup-title">
          {t(mode === 'pin' ? 'devices.messaging.unlock.title' : 'devices.messaging.recover.title')}
        </ModalTitle>
        <p className="messaging-muted">
          {t(mode === 'pin' ? 'devices.messaging.unlock.body' : 'devices.messaging.recover.body')}
        </p>
        {mode === 'pin' ? (
          <PinField id="messaging-unlock-pin" label={t('devices.messaging.pin.label')} value={input} onChange={setInput} />
        ) : (
          <>
            <label className="field-label" htmlFor="messaging-recovery-input">{t('devices.messaging.recover.label')}</label>
            <textarea
              id="messaging-recovery-input"
              className="field-input messaging-key-input"
              dir="ltr"
              rows={3}
              autoComplete="off"
              spellCheck={false}
              value={input}
              onChange={(event) => setInput(event.target.value)}
            />
          </>
        )}
        {error && <p className="messaging-error" role="alert">{error}</p>}
        {wrongPin && <p className="messaging-caption">{t('devices.messaging.unlock.attemptsLeft', { count: attemptsLeft })}</p>}
        <div className="messaging-actions">
          <button type="submit" className="btn btn-primary" disabled={pending || input.trim() === ''}>
            {pending ? t('devices.messaging.setup.finishing') : t(mode === 'pin' ? 'devices.messaging.unlock.submit' : 'devices.messaging.recover.submit')}
          </button>
          <button type="button" className="btn btn-secondary" onClick={onClose} disabled={pending}>
            {t('devices.messaging.buttons.cancel')}
          </button>
        </div>
      </form>
    </Modal>
  );
}
