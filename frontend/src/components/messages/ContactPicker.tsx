import { useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { CONTACTS_KEY } from '../../context/messages/queryKeys';
import { messagesApi, type Contact } from '../../services/messagesApi';
import { useAsyncAction } from '../../hooks/useAsyncAction';
import { initials } from './conversationTitle';

interface ContactPickerProps {
  multiple: boolean;
  chosen: Contact[];
  onChange: (chosen: Contact[]) => void;
  exclude?: number[];
}

export default function ContactPicker({ multiple, chosen, onChange, exclude = [] }: ContactPickerProps) {
  const { t } = useTranslation();
  const contacts = useQuery({ queryKey: CONTACTS_KEY, queryFn: messagesApi.contacts });
  const [filter, setFilter] = useState('');
  const [email, setEmail] = useState('');
  const [found, setFound] = useState<Contact[]>([]);
  const lookup = useAsyncAction();

  const isChosen = (contact: Contact) => chosen.some((each) => each.id === contact.id);
  const toggle = (contact: Contact) => {
    if (isChosen(contact)) {
      onChange(chosen.filter((each) => each.id !== contact.id));
    } else {
      onChange(multiple ? [...chosen, contact] : [contact]);
    }
  };

  const find = async (event: FormEvent) => {
    event.preventDefault();
    if (!email.trim()) return;
    await lookup.run(async () => {
      const contact = await messagesApi.lookup(email.trim());
      setFound((known) => [...known.filter((each) => each.id !== contact.id), contact]);
      if (!isChosen(contact)) onChange(multiple ? [...chosen, contact] : [contact]);
      setEmail('');
    }, t('messages.new.notFound'));
  };

  const all = [...found, ...(contacts.data ?? []).filter((contact) => !found.some((each) => each.id === contact.id))]
    .filter((contact) => !exclude.includes(contact.id))
    .filter((contact) => contact.name.toLowerCase().includes(filter.trim().toLowerCase()));

  return (
    <div className="contact-picker">
      <label className="form-group">
        <span>{t('messages.new.filter')}</span>
        <input type="search" value={filter} onChange={(event) => setFilter(event.target.value)} />
      </label>
      {contacts.isPending && <p className="messages-muted">{t('app.loading')}</p>}
      {!contacts.isPending && !all.length && <p className="messages-muted">{t('messages.new.noContacts')}</p>}
      <ul className="contact-options">
        {all.map((contact) => (
          <li key={contact.id}>
            <label className={`contact-option${contact.messaging ? '' : ' is-disabled'}`}>
              <input
                type={multiple ? 'checkbox' : 'radio'}
                name="contact"
                checked={isChosen(contact)}
                disabled={!contact.messaging}
                onChange={() => toggle(contact)}
              />
              <span className="conversation-avatar" aria-hidden="true">{initials(contact.name)}</span>
              <span className="contact-option-text">
                <span>{contact.name}</span>
                <span className="messages-muted">{contact.messaging ? contact.email : t('messages.new.notSetUp')}</span>
              </span>
            </label>
          </li>
        ))}
      </ul>
      <form className="contact-lookup" onSubmit={find}>
        <label className="form-group">
          <span>{t('messages.new.byEmail')}</span>
          <input type="email" value={email} onChange={(event) => setEmail(event.target.value)} />
        </label>
        <button type="submit" className="btn btn-secondary" disabled={lookup.pending || !email.trim()}>
          {t('messages.new.find')}
        </button>
      </form>
      <p className="messages-caption">{t('messages.new.byEmailHint')}</p>
      {lookup.error && <p className="messages-error" role="alert">{lookup.error}</p>}
    </div>
  );
}
