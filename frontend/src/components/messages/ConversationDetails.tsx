import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import Modal, { ModalTitle } from '../ui/Modal';
import { useMessages } from '../../context/MessagesContext';
import { useAsyncAction } from '../../hooks/useAsyncAction';
import type { Contact, ConversationMemberInfo, HistoryVisibility, WhoCanAdd } from '../../services/messagesApi';
import Icon from '../Icon';
import ContactPicker from './ContactPicker';
import SafetyNumber from './SafetyNumber';
import { initials, others } from './conversationTitle';
import '../../styles/components/Forms.css';

function AddPeople({ exclude, onClose }: { exclude: number[]; onClose: () => void }) {
  const { t } = useTranslation();
  const { addMembers } = useMessages();
  const [chosen, setChosen] = useState<Contact[]>([]);
  const { pending, error, run } = useAsyncAction();

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (await run(() => addMembers(chosen.map((contact) => contact.id)), t('messages.errors.generic'))) onClose();
  };

  return (
    <Modal onClose={pending ? () => undefined : onClose} overlayClassName="modal-overlay" contentClassName="form-modal" busy={pending}>
      <form className="form-container messages-dialog" onSubmit={submit}>
        <ModalTitle>{t('messages.details.addPeople')}</ModalTitle>
        <ContactPicker multiple chosen={chosen} onChange={setChosen} exclude={exclude} />
        {error && <p className="messages-error" role="alert">{error}</p>}
        <div className="form-actions">
          <button type="submit" className="btn btn-primary" disabled={pending || !chosen.length}>{t('messages.details.add')}</button>
          <button type="button" className="btn btn-secondary" onClick={onClose} disabled={pending}>{t('messages.cancel')}</button>
        </div>
      </form>
    </Modal>
  );
}

export default function ConversationDetails({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const { selected, userId, nameOf, configure, rename, removeMember, setRole, leave, block } = useMessages();
  const [name, setName] = useState(selected ? nameOf(selected) ?? '' : '');
  const [adding, setAdding] = useState(false);
  const [verifying, setVerifying] = useState<ConversationMemberInfo | null>(null);
  const action = useAsyncAction();

  if (!selected) return null;
  const conversation = selected;
  const group = conversation.kind === 'GROUP';
  const admin = conversation.me.role === 'ADMIN';
  const canAdd = group && (admin || (conversation.whoCanAdd === 'MEMBERS' && conversation.me.state === 'ACTIVE'));
  const other = others(conversation, userId)[0];
  const act = (work: () => Promise<unknown>) => void action.run(work, t('messages.errors.generic'));

  const submitName = (event: FormEvent) => {
    event.preventDefault();
    if (name.trim() && name.trim() !== nameOf(conversation)) act(() => rename(name.trim()));
  };

  return (
    <aside className="thread-details" aria-label={t('messages.details.title')}>
      <div className="thread-details-head">
        <h2>{t('messages.details.title')}</h2>
        <button type="button" className="btn-ghost" aria-label={t('messages.close')} onClick={onClose}>
          <Icon name="close" />
        </button>
      </div>

      {group && admin && (
        <form className="details-section" onSubmit={submitName}>
          <label className="form-group">
            <span>{t('messages.details.name')}</span>
            <input value={name} maxLength={100} onChange={(event) => setName(event.target.value)} />
          </label>
          <button type="submit" className="btn btn-secondary btn-sm" disabled={action.pending}>{t('messages.details.rename')}</button>
        </form>
      )}

      <section className="details-section" aria-label={t('messages.details.members')}>
        <h3>{t('messages.details.members')}</h3>
        <ul className="details-members">
          {conversation.members.map((member) => {
            const me = member.userId === userId;
            return (
              <li key={member.userId}>
                <span className="conversation-avatar" aria-hidden="true">{initials(member.name)}</span>
                <span className="details-member-name">
                  {member.name}
                  {me && <span className="details-badge">{t('messages.details.you')}</span>}
                  {group && member.role === 'ADMIN' && <span className="details-badge">{t('messages.details.admin')}</span>}
                  {member.state === 'REQUEST' && <span className="details-badge">{t('messages.details.invited')}</span>}
                </span>
                {!me && (
                  <span className="details-member-actions">
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => setVerifying(member)}>
                      {t('messages.details.verify')}
                    </button>
                    {group && admin && (
                      <>
                        <button type="button" className="btn btn-ghost btn-sm" disabled={action.pending}
                          onClick={() => act(() => setRole(member.userId, member.role === 'ADMIN' ? 'MEMBER' : 'ADMIN'))}>
                          {t(member.role === 'ADMIN' ? 'messages.details.unmakeAdmin' : 'messages.details.makeAdmin')}
                        </button>
                        <button type="button" className="btn btn-ghost btn-sm is-danger" disabled={action.pending}
                          onClick={() => act(() => removeMember(member.userId))}>
                          {t('messages.details.remove')}
                        </button>
                      </>
                    )}
                  </span>
                )}
              </li>
            );
          })}
        </ul>
        {canAdd && (
          <button type="button" className="btn btn-secondary btn-sm" onClick={() => setAdding(true)}>
            <Icon name="plus" size="sm" />{t('messages.details.addPeople')}
          </button>
        )}
      </section>

      {group && admin && (
        <section className="details-section" aria-label={t('messages.settings.title')}>
          <h3>{t('messages.settings.title')}</h3>
          <label className="form-group">
            <span>{t('messages.settings.history')}</span>
            <select value={conversation.historyVisibility} disabled={action.pending}
              onChange={(event) => act(() => configure({ historyVisibility: event.target.value as HistoryVisibility }))}>
              <option value="JOINED">{t('messages.settings.historyJoined')}</option>
              <option value="DAYS_30">{t('messages.settings.historyDays')}</option>
              <option value="ALL">{t('messages.settings.historyAll')}</option>
            </select>
          </label>
          <label className="form-group">
            <span>{t('messages.settings.whoCanAdd')}</span>
            <select value={conversation.whoCanAdd} disabled={action.pending}
              onChange={(event) => act(() => configure({ whoCanAdd: event.target.value as WhoCanAdd }))}>
              <option value="ADMINS">{t('messages.settings.admins')}</option>
              <option value="MEMBERS">{t('messages.settings.members')}</option>
            </select>
          </label>
        </section>
      )}

      <section className="details-section" aria-label={t('messages.details.preferences')}>
        <h3>{t('messages.details.preferences')}</h3>
        {(['muted', 'pinned', 'archived'] as const).map((setting) => (
          <label key={setting} className="details-check">
            <input type="checkbox" checked={conversation.me[setting]} disabled={action.pending}
              onChange={(event) => act(() => configure({ [setting]: event.target.checked }))} />
            <span>{t(`messages.details.${setting}`)}</span>
          </label>
        ))}
      </section>

      <section className="details-section">
        {group ? (
          <button type="button" className="btn btn-danger btn-sm" disabled={action.pending} onClick={() => act(leave)}>
            {t('messages.details.leave')}
          </button>
        ) : other && (
          <button type="button" className="btn btn-danger btn-sm" disabled={action.pending} onClick={() => act(() => block(other.userId))}>
            {t('messages.details.block', { name: other.name })}
          </button>
        )}
      </section>
      {action.error && <p className="messages-error" role="alert">{action.error}</p>}

      {adding && <AddPeople exclude={conversation.members.map((member) => member.userId)} onClose={() => setAdding(false)} />}
      {verifying && (
        <SafetyNumber conversationId={conversation.id} person={verifying} onClose={() => setVerifying(null)} />
      )}
    </aside>
  );
}
