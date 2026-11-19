import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useMessages } from '../../context/MessagesContext';
import type { Conversation } from '../../services/messagesApi';
import Icon from '../Icon';
import NewConversation from './NewConversation';
import { conversationTitle, initials } from './conversationTitle';

export default function ConversationList() {
  const { t, i18n } = useTranslation();
  const { conversations, loading, selected, select, userId, nameOf } = useMessages();
  const [query, setQuery] = useState('');
  const [showArchived, setShowArchived] = useState(false);
  const [creating, setCreating] = useState(false);

  const titleOf = (conversation: Conversation) => conversationTitle(conversation, userId, nameOf(conversation), t, i18n.language);
  const matching = conversations.filter((conversation) =>
    titleOf(conversation).toLowerCase().includes(query.trim().toLowerCase()));
  const requests = matching.filter((conversation) => conversation.me.state === 'REQUEST');
  const archived = matching.filter((conversation) => conversation.me.state !== 'REQUEST' && conversation.me.archived);
  const chats = matching
    .filter((conversation) => conversation.me.state !== 'REQUEST' && !conversation.me.archived)
    .sort((a, b) => Number(b.me.pinned) - Number(a.me.pinned));

  const row = (conversation: Conversation) => {
    const title = titleOf(conversation);
    return (
      <li key={conversation.id}>
        <button
          type="button"
          className={`conversation-row${selected?.id === conversation.id ? ' is-selected' : ''}`}
          aria-current={selected?.id === conversation.id ? 'true' : undefined}
          onClick={() => select(conversation.id)}
        >
          <span className="conversation-avatar" aria-hidden="true">
            {conversation.kind === 'GROUP' ? <Icon name="people" size="sm" /> : initials(title)}
          </span>
          <span className="conversation-row-text">
            <span className="conversation-row-title">{title}</span>
            <span className="conversation-row-meta">
              {conversation.me.state === 'REQUEST' ? t('messages.list.request')
                : new Date(conversation.lastActivityAt).toLocaleDateString(i18n.language)}
            </span>
          </span>
          {conversation.me.pinned && <Icon name="star" size="sm" className="conversation-row-pin" />}
          {conversation.me.muted && <span className="visually-hidden">{t('messages.list.muted')}</span>}
          {conversation.unread > 0 && (
            <span className="conversation-unread" aria-label={t('messages.list.unread', { count: conversation.unread })}>
              {conversation.unread}
            </span>
          )}
        </button>
      </li>
    );
  };

  return (
    <aside className="conversation-list" aria-label={t('messages.list.label')}>
      <div className="conversation-list-head">
        <h1>{t('messages.title')}</h1>
        <button type="button" className="btn btn-primary btn-sm" onClick={() => setCreating(true)}>
          <Icon name="plus" size="sm" />
          <span>{t('messages.list.new')}</span>
        </button>
      </div>
      <label className="conversation-search">
        <Icon name="search" size="sm" />
        <span className="visually-hidden">{t('messages.list.search')}</span>
        <input type="search" value={query} placeholder={t('messages.list.search')} onChange={(event) => setQuery(event.target.value)} />
      </label>
      {loading && <p className="conversation-list-note">{t('app.loading')}</p>}
      {!loading && !conversations.length && <p className="conversation-list-note">{t('messages.list.none')}</p>}
      {requests.length > 0 && (
        <section aria-label={t('messages.list.requests')}>
          <h2 className="conversation-list-section">{t('messages.list.requests')}</h2>
          <ul className="conversation-rows">{requests.map(row)}</ul>
        </section>
      )}
      <ul className="conversation-rows">{chats.map(row)}</ul>
      {archived.length > 0 && (
        <>
          <button type="button" className="conversation-archive-toggle" aria-expanded={showArchived}
            onClick={() => setShowArchived((open) => !open)}>
            <Icon name={showArchived ? 'chevron-down' : 'chevron-right'} size="sm" />
            {t('messages.list.archived', { count: archived.length })}
          </button>
          {showArchived && <ul className="conversation-rows">{archived.map(row)}</ul>}
        </>
      )}
      {creating && <NewConversation onClose={() => setCreating(false)} />}
    </aside>
  );
}
