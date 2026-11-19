import { useState, type FormEvent } from 'react';
import { toast } from 'react-toastify';
import { useTranslation } from 'react-i18next';
import Modal, { ModalTitle } from '../ui/Modal';
import { deviceVault, VaultError } from '../../crypto/client';
import { fromBase64 } from '../../crypto/bytes';
import { normalizeRecoveryKey } from '../../crypto/recoveryKey';
import { cryptoApi } from '../../services/cryptoApi';
import { useAsyncAction } from '../../hooks/useAsyncAction';
import '../../styles/components/Forms.css';

interface MessagingRecoverProps {
  onRegister: () => Promise<void>;
  onClose: () => void;
}

export default function MessagingRecover({ onRegister, onClose }: MessagingRecoverProps) {
  const { t } = useTranslation();
  const [input, setInput] = useState('');
  const { pending, error, setError, run } = useAsyncAction();

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const recoveryKey = normalizeRecoveryKey(input);
    if (!recoveryKey) {
      setError(t('devices.messaging.errors.malformedKey'));
      return;
    }
    const done = await run(async () => {
      const backup = await cryptoApi.identityBackup();
      try {
        await deviceVault().restoreIdentity(recoveryKey, {
          publicKey: fromBase64(backup.publicKey),
          wrappedPrivate: fromBase64(backup.wrappedPrivate),
        });
      } catch (failure) {
        if (failure instanceof VaultError && failure.name === 'RecoveryKeyMismatchError') {
          throw new Error(t('devices.messaging.errors.wrongKey'), { cause: failure });
        }
        throw failure;
      }
      await onRegister();
    }, t('devices.messaging.errors.generic'));
    if (done) {
      toast.success(t('devices.messaging.setup.done'));
      onClose();
    }
  };

  return (
    <Modal onClose={pending ? () => undefined : onClose} overlayClassName="modal-overlay" contentClassName="form-modal messaging-setup" busy={pending}>
      <form className="messaging-setup-step" onSubmit={submit}>
        <ModalTitle className="messaging-setup-title">{t('devices.messaging.recover.title')}</ModalTitle>
        <p className="messaging-muted">{t('devices.messaging.recover.body')}</p>
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
        {error && <p className="messaging-error" role="alert">{error}</p>}
        <div className="messaging-actions">
          <button type="submit" className="btn btn-primary" disabled={pending || input.trim() === ''}>
            {pending ? t('devices.messaging.setup.finishing') : t('devices.messaging.recover.submit')}
          </button>
          <button type="button" className="btn btn-secondary" onClick={onClose} disabled={pending}>
            {t('devices.messaging.buttons.cancel')}
          </button>
        </div>
      </form>
    </Modal>
  );
}
