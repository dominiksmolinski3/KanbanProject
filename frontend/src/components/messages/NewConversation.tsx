import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import Modal, { ModalTitle } from '../ui/Modal';
import FormTabs from '../ui/FormTabs';
import { useMessages } from '../../context/MessagesContext';
import { useAsyncAction } from '../../hooks/useAsyncAction';
import type { Contact, HistoryVisibility } from '../../services/messagesApi';
import ContactPicker from './ContactPicker';
import '../../styles/components/Forms.css';

const MAX_NAME = 100;

export default function NewConversation({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const { start } = useMessages();
  const [kind, setKind] = useState<'DIRECT' | 'GROUP'>('DIRECT');
  const [chosen, setChosen] = useState<Contact[]>([]);
  const [name, setName] = useState('');
  const [history, setHistory] = useState<HistoryVisibility>('JOINED');
  const { pending, error, setError, run } = useAsyncAction();

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!chosen.length) {
      setError(t('messages.new.pickSomeone'));
      return;
    }
    const done = await run(async () => {
      await start({ kind, userIds: chosen.map((contact) => contact.id), ...(kind === 'GROUP' ? { historyVisibility: history } : {}) },
        kind === 'GROUP' && name.trim() ? name.trim() : undefined);
    }, t('messages.errors.generic'));
    if (done) onClose();
  };

  return (
    <Modal onClose={pending ? () => undefined : onClose} overlayClassName="modal-overlay" contentClassName="form-modal" busy={pending}>
      <form className="form-container messages-dialog" onSubmit={submit}>
        <ModalTitle>{t('messages.new.title')}</ModalTitle>
        <FormTabs
          tabs={[{ id: 'DIRECT', label: t('messages.new.direct') }, { id: 'GROUP', label: t('messages.new.group') }]}
          active={kind}
          onChange={(next) => {
            setKind(next);
            setChosen((current) => (next === 'DIRECT' ? current.slice(0, 1) : current));
          }}
        />
        {kind === 'GROUP' && (
          <>
            <label className="form-group">
              <span>{t('messages.new.name')}</span>
              <input value={name} maxLength={MAX_NAME} onChange={(event) => setName(event.target.value)} />
            </label>
            <label className="form-group">
              <span>{t('messages.settings.history')}</span>
              <select value={history} onChange={(event) => setHistory(event.target.value as HistoryVisibility)}>
                <option value="JOINED">{t('messages.settings.historyJoined')}</option>
                <option value="DAYS_30">{t('messages.settings.historyDays')}</option>
                <option value="ALL">{t('messages.settings.historyAll')}</option>
              </select>
            </label>
          </>
        )}
        <ContactPicker multiple={kind === 'GROUP'} chosen={chosen} onChange={setChosen} />
        {error && <p className="messages-error" role="alert">{error}</p>}
        <div className="form-actions">
          <button type="submit" className="btn btn-primary" disabled={pending}>{t('messages.new.start')}</button>
          <button type="button" className="btn btn-secondary" onClick={onClose} disabled={pending}>{t('messages.cancel')}</button>
        </div>
      </form>
    </Modal>
  );
}
