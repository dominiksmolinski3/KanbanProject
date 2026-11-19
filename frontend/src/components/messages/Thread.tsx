import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useMessages } from '../../context/MessagesContext';
import { useAsyncAction } from '../../hooks/useAsyncAction';
import type { ArchivedMessage } from '../../crypto/engine';
import Icon from '../Icon';
import Composer, { type Draft } from './Composer';
import ConversationDetails from './ConversationDetails';
import MessageRow from './MessageRow';
import { conversationTitle, others } from './conversationTitle';

export default function Thread() {
  const { t, i18n } = useTranslation();
  const {
    selected, select, messages, threadState, typing, userId, nameOf, send, edit, remove, react, notifyTyping,
    accept, decline, block,
  } = useMessages();
  const [draft, setDraft] = useState<Draft>(null);
  const [details, setDetails] = useState(false);
  const request = useAsyncAction();
  const end = useRef<HTMLDivElement>(null);

  useEffect(() => {
    end.current?.scrollIntoView?.({ block: 'end' });
  }, [messages.length]);

  if (!selected) return null;
  const conversation = selected;
  const title = conversationTitle(conversation, userId, nameOf(conversation), t, i18n.language);
  const people = new Map(conversation.members.map((member) => [member.userId, member.name]));
  const personName = (id: number | null) => (id === null ? t('messages.unknownPerson') : people.get(id) ?? t('messages.formerMember'));
  const byId = new Map(messages.map((message) => [message.id, message]));
  const blocked = threadState === 'waiting' || threadState === 'removed' || conversation.me.state === 'REQUEST';
  const other = others(conversation, userId)[0];

  const onSend = async (text: string) => {
    if (draft?.mode === 'edit') {
      await edit(draft.message.id, text);
    } else {
      await send(text, draft?.mode === 'reply' ? draft.message.id : null);
    }
    setDraft(null);
  };

  let lastDay = '';
  const rows = messages.map((message: ArchivedMessage, index) => {
    const day = new Date(message.sentAt).toLocaleDateString(i18n.language, { dateStyle: 'medium' });
    const separator = day !== lastDay ? <li key={`day-${day}`} className="message-day"><span>{day}</span></li> : null;
    lastDay = day;
    const previous = messages[index - 1];
    const mine = message.senderId === userId;
    const showSender = conversation.kind === 'GROUP' && !mine && message.kind === 'text'
      && (previous?.senderId !== message.senderId || previous?.kind !== 'text');
    return [
      separator,
      <MessageRow
        key={message.id}
        message={message}
        mine={mine}
        userId={userId}
        senderName={showSender ? personName(message.senderId) : null}
        nameOf={personName}
        replied={message.replyTo ? byId.get(message.replyTo) ?? null : undefined}
        onReply={(target) => setDraft({ mode: 'reply', message: target })}
        onEdit={(target) => setDraft({ mode: 'edit', message: target })}
        onDelete={(target) => void remove(target.id)}
        onReact={(target, emoji, on) => void react(target.id, emoji, on)}
      />,
    ];
  });

  return (
    <div className={`thread-layout${details ? ' thread-layout--details' : ''}`}>
      <section className="thread" aria-label={title}>
        <header className="thread-head">
          <button type="button" className="btn-ghost thread-back" aria-label={t('messages.back')} onClick={() => select(null)}>
            <Icon name="chevron-left" />
          </button>
          <div className="thread-title">
            <h2>{title}</h2>
            <span className="thread-subtitle">
              <Icon name="lock" size="sm" />
              {conversation.kind === 'GROUP'
                ? t('messages.thread.members', { count: conversation.members.length })
                : t('messages.thread.encrypted')}
            </span>
          </div>
          <button type="button" className="btn-ghost" aria-expanded={details} aria-label={t('messages.details.open')}
            onClick={() => setDetails((open) => !open)}>
            <Icon name="info" />
          </button>
        </header>

        {conversation.me.state === 'REQUEST' && (
          <div className="thread-banner" role="region" aria-label={t('messages.request.title')}>
            <p>{t(conversation.kind === 'DIRECT' ? 'messages.request.direct' : 'messages.request.group', { name: personName(other?.userId ?? null) })}</p>
            <div className="thread-banner-actions">
              <button type="button" className="btn btn-primary btn-sm" disabled={request.pending}
                onClick={() => void request.run(accept, t('messages.errors.generic'))}>{t('messages.request.accept')}</button>
              <button type="button" className="btn btn-secondary btn-sm" disabled={request.pending}
                onClick={() => void request.run(decline, t('messages.errors.generic'))}>{t('messages.request.decline')}</button>
              {conversation.kind === 'DIRECT' && other && (
                <button type="button" className="btn btn-danger btn-sm" disabled={request.pending}
                  onClick={() => void request.run(() => block(other.userId), t('messages.errors.generic'))}>
                  {t('messages.request.block')}
                </button>
              )}
            </div>
            {request.error && <p className="messages-error" role="alert">{request.error}</p>}
          </div>
        )}
        {threadState === 'waiting' && <p className="thread-state">{t('messages.thread.waiting')}</p>}
        {threadState === 'removed' && <p className="thread-state">{t('messages.thread.removed')}</p>}
        {threadState === 'failed' && <p className="thread-state messages-error">{t('messages.thread.failed')}</p>}

        <ol className="message-list" role="log" aria-live="polite" aria-label={t('messages.thread.log')}>
          {threadState === 'loading' && !messages.length && <li className="message-notice">{t('app.loading')}</li>}
          {threadState !== 'loading' && !messages.length && <li className="message-notice">{t('messages.thread.none')}</li>}
          {rows}
        </ol>
        <div ref={end} />

        {typing.length > 0 && (
          <p className="thread-typing" aria-live="polite">
            {typing.length === 1
              ? t('messages.thread.typingOne', { name: personName(typing[0]) })
              : t('messages.thread.typingMany', { count: typing.length })}
          </p>
        )}
        <Composer
          disabled={blocked}
          draft={draft}
          draftAuthor={draft ? personName(draft.message.senderId) : ''}
          onCancelDraft={() => setDraft(null)}
          onSend={onSend}
          onTyping={notifyTyping}
        />
      </section>
      {details && <ConversationDetails onClose={() => setDetails(false)} />}
    </div>
  );
}
