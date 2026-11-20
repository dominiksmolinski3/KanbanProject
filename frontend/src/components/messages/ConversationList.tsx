import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useMessages } from '../../context/MessagesContext';
import type { ArchivedMessage } from '../../crypto/engine';
import type { Conversation } from '../../services/messagesApi';
import Icon from '../Icon';
import NewConversation from './NewConversation';
import { conversationTitle, initials } from './conversationTitle';

export default function ConversationList() {
  const { t, i18n } = useTranslation();
  const { conversations, loading, selected, select, userId, nameOf, mentioned, search } = useMessages();
  const [query, setQuery] = useState('');
  const [hits, setHits] = useState<ArchivedMessage[]>([]);
  const [searching, setSearching] = useState(false);

  useEffect(() => {
    const needle = query.trim();
    if (needle.length < 2) {
      setHits([]);
      setSearching(false);
      return undefined;
    }
    let current = true;
    setSearching(true);
    const timer = setTimeout(() => {
      search(needle).then((found) => current && setHits(found), () => current && setHits([]))
        .finally(() => current && setSearching(false));
    }, 250);
    return () => {
      current = false;
      clearTimeout(timer);
    };
  }, [query, search]);

  const open = (hit: ArchivedMessage) => {
    select(hit.conversationId);
    setTimeout(() => {
      const row = document.getElementById(`message-${hit.id}`);
      row?.scrollIntoView?.({ block: 'center' });
      row?.classList.add('is-found');
    }, 300);
  };
  const [showArchived, setShowArchived] = useState(false);
  const [creating, setCreating] = useState(false);

  const titleOf = (conversation: Conversation) => conversationTitle(conversation, userId, nameOf(conversation), t, i18n.language);
  const matching = conversations.filter((conversation) =>
    titleOf(conversation).toLowerCase().includes(query.trim().toLowerCase()));
  const requests = matching.filter((conversation) => conversation.me.state === 'REQUEST');
  const archived = matching.filter((conversation) => conversation.me.state !== 'REQUEST' && conversation.me.archived);
  const active = matching
    .filter((conversation) => conversation.me.state !== 'REQUEST' && !conversation.me.archived)
    .sort((a, b) => Number(b.me.pinned) - Number(a.me.pinned));
  const boardChannels = active.filter((conversation) => conversation.kind === 'BOARD');
  const chats = active.filter((conversation) => conversation.kind !== 'BOARD');

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
            {conversation.kind === 'DIRECT' ? initials(title) : <Icon name={conversation.kind === 'BOARD' ? 'board' : 'people'} size="sm" />}
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
          {mentioned.includes(conversation.id) && (
            <span className="conversation-mention" role="img" aria-label={t('messages.list.mentioned')}>@</span>
          )}
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
      {query.trim().length >= 2 && (
        <section aria-label={t('messages.list.results')}>
          <h2 className="conversation-list-section">{t('messages.list.results')}</h2>
          {searching && !hits.length && <p className="conversation-list-note">{t('messages.list.searching')}</p>}
          {!searching && !hits.length && <p className="conversation-list-note">{t('messages.list.noResults')}</p>}
          <ul className="conversation-rows">
            {hits.map((hit) => {
              const where = conversations.find((each) => each.id === hit.conversationId);
              return (
                <li key={`${hit.conversationId}-${hit.id}`}>
                  <button type="button" className="conversation-row conversation-hit" onClick={() => open(hit)}>
                    <span className="conversation-row-text">
                      <span className="conversation-row-title">{where ? titleOf(where) : ''}</span>
                      <span className="conversation-hit-snippet">{hit.text || hit.files?.[0]?.name}</span>
                      <span className="conversation-row-meta">{new Date(hit.sentAt).toLocaleDateString(i18n.language)}</span>
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>
        </section>
      )}
      {loading && <p className="conversation-list-note">{t('app.loading')}</p>}
      {!loading && !conversations.length && <p className="conversation-list-note">{t('messages.list.none')}</p>}
      {requests.length > 0 && (
        <section aria-label={t('messages.list.requests')}>
          <h2 className="conversation-list-section">{t('messages.list.requests')}</h2>
          <ul className="conversation-rows">{requests.map(row)}</ul>
        </section>
      )}
      {boardChannels.length > 0 && (
        <section aria-label={t('messages.list.boards')}>
          <h2 className="conversation-list-section">{t('messages.list.boards')}</h2>
          <ul className="conversation-rows">{boardChannels.map(row)}</ul>
        </section>
      )}
      {chats.length > 0 && boardChannels.length > 0 && (
        <h2 className="conversation-list-section">{t('messages.list.people')}</h2>
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
