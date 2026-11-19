import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import Modal, { ModalTitle } from '../ui/Modal';
import { useMessages } from '../../context/MessagesContext';
import type { Verification } from '../../context/messages/messenger';
import { safetyNumberGroups } from '../../crypto/safetyNumber';
import '../../styles/components/Forms.css';

interface SafetyNumberProps {
  conversationId: number;
  person: { userId: number; name: string };
  onClose: () => void;
}

export default function SafetyNumber({ conversationId, person, onClose }: SafetyNumberProps) {
  const { t } = useTranslation();
  const { verification, setVerified } = useMessages();
  const [found, setFound] = useState<Verification | null | undefined>(undefined);

  useEffect(() => {
    let current = true;
    verification(conversationId, person.userId)
      .then((result) => current && setFound(result))
      .catch(() => current && setFound(null));
    return () => {
      current = false;
    };
  }, [verification, conversationId, person.userId]);

  const toggle = async () => {
    if (!found) return;
    await setVerified(person.userId, found.identityKey, !found.verified);
    setFound({ ...found, verified: !found.verified });
  };

  return (
    <Modal onClose={onClose} overlayClassName="modal-overlay" contentClassName="form-modal">
      <div className="form-container messages-dialog">
        <ModalTitle>{t('messages.verify.title', { name: person.name })}</ModalTitle>
        <p className="messages-muted">{t('messages.verify.body', { name: person.name })}</p>
        {found === undefined && <p>{t('app.loading')}</p>}
        {found === null && <p className="messages-error" role="alert">{t('messages.verify.unavailable')}</p>}
        {found && (
          <>
            <div className="safety-number" aria-label={t('messages.verify.number')}>
              {safetyNumberGroups(found.digits).map((group, index) => <span key={index}>{group}</span>)}
            </div>
            <p className={found.verified ? 'messages-ok' : 'messages-muted'}>
              {t(found.verified ? 'messages.verify.verified' : 'messages.verify.notVerified')}
            </p>
          </>
        )}
        <div className="form-actions">
          {found && (
            <button type="button" className="btn btn-primary" onClick={() => void toggle()}>
              {t(found.verified ? 'messages.verify.unmark' : 'messages.verify.mark')}
            </button>
          )}
          <button type="button" className="btn btn-secondary" onClick={onClose}>{t('messages.close')}</button>
        </div>
      </div>
    </Modal>
  );
}
