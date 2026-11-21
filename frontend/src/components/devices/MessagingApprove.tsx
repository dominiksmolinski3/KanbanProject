import { useState, type FormEvent } from 'react';
import { toast } from 'react-toastify';
import { useTranslation } from 'react-i18next';
import Modal, { ModalTitle } from '../ui/Modal';
import { useAsyncAction } from '../../hooks/useAsyncAction';
import { approveLink, BadLinkCodeError } from './messagingFlows';
import '../../styles/components/Forms.css';

interface MessagingApproveProps {
  initialCode?: string;
  onClose: () => void;
}

export default function MessagingApprove({ initialCode = '', onClose }: MessagingApproveProps) {
  const { t } = useTranslation();
  const [code, setCode] = useState(initialCode);
  const { pending, error, run } = useAsyncAction();

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const done = await run(async () => {
      try {
        await approveLink(code);
      } catch (failure) {
        if (failure instanceof BadLinkCodeError) {
          throw new Error(t('devices.messaging.approve.badCode'), { cause: failure });
        }
        throw failure;
      }
    }, t('devices.messaging.errors.generic'));
    if (done) {
      toast.success(t('devices.messaging.approve.done'));
      onClose();
    }
  };

  return (
    <Modal onClose={pending ? () => undefined : onClose} overlayClassName="modal-overlay"
      contentClassName="form-modal messaging-setup" busy={pending}>
      <form className="form-container messaging-setup-step" onSubmit={submit}>
        <ModalTitle className="messaging-setup-title">{t('devices.messaging.approve.title')}</ModalTitle>
        <p className="messaging-muted">{t('devices.messaging.approve.body')}</p>
        <label className="field-label" htmlFor="messaging-link-input">{t('devices.messaging.approve.label')}</label>
        <textarea
          id="messaging-link-input"
          className="field-input messaging-key-input"
          dir="ltr"
          rows={3}
          autoComplete="off"
          spellCheck={false}
          value={code}
          onChange={(event) => setCode(event.target.value)}
        />
        <p className="messaging-caption">{t('devices.messaging.approve.warning')}</p>
        {error && <p className="messaging-error" role="alert">{error}</p>}
        <div className="messaging-actions">
          <button type="submit" className="btn btn-primary" disabled={pending || code.trim() === ''}>
            {t('devices.messaging.approve.submit')}
          </button>
          <button type="button" className="btn btn-secondary" onClick={onClose} disabled={pending}>
            {t('devices.messaging.buttons.cancel')}
          </button>
        </div>
      </form>
    </Modal>
  );
}
