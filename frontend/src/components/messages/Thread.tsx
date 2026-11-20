import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useMessages } from '../../context/MessagesContext';
import { useAsyncAction } from '../../hooks/useAsyncAction';
import type { ArchivedMessage } from '../../crypto/engine';
import Icon from '../Icon';
import Composer, { type Draft, type Outgoing } from './Composer';
import ConversationDetails from './ConversationDetails';
import EarlierMessages from './EarlierMessages';
import MessageRow, { type DeliveryStatus } from './MessageRow';
import ThreadPanel from './ThreadPanel';
import { conversationTitle, others } from './conversationTitle';
import { disappearLabel } from './disappear';
import type { Person } from './mentions';

export default function Thread({ variant = 'page' }: { variant?: 'page' | 'dock' }) {
  const { t, i18n } = useTranslation();
  const {
    selected, select, messages, threadState, typing, userId, nameOf, send, edit, remove, react, notifyTyping,
    accept, decline, block,
  } = useMessages();
  const [draft, setDraft] = useState<Draft>(null);
  const [details, setDetails] = useState(false);
  const [panelRoot, setPanelRoot] = useState<string | null>(null);
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
  const readOnly = conversation.me.role === 'VIEWER';
  const dock = variant === 'dock';
  const other = others(conversation, userId)[0];

  const mentionable: Person[] = conversation.members
    .filter((member) => member.userId !== userId && member.state === 'ACTIVE')
    .map((member) => ({ userId: member.userId, name: member.name }));
  const everyone: Person[] = conversation.members.map((member) => ({ userId: member.userId, name: member.name }));
  const repliesTo = new Map<string, ArchivedMessage[]>();
  messages.forEach((message) => {
    if (message.threadId && byId.has(message.threadId)) {
      repliesTo.set(message.threadId, [...(repliesTo.get(message.threadId) ?? []), message]);
    }
  });
  const root = panelRoot ? byId.get(panelRoot) ?? null : null;

  const onSend = async (text: string, outgoing: Outgoing) => {
    if (draft?.mode === 'edit') {
      await edit(draft.message.id, text);
    } else {
      await send(text, draft?.mode === 'reply' ? draft.message.id : null,
        { mentions: outgoing.mentions, files: outgoing.files });
    }
    setDraft(null);
  };

  const statusOf = (message: ArchivedMessage): DeliveryStatus | null => {
    if (message.kind !== 'text' || message.senderId !== userId || message.deleted) return null;
    const readers = conversation.members.filter((member) => member.userId !== userId && member.joinedSeq <= message.seq);
    if (readers.length && readers.every((member) => member.readSeq >= message.seq)) return 'read';
    if (readers.length && readers.every((member) => member.deliveredSeq >= message.seq)) return 'delivered';
    return 'sent';
  };

  const renderMessage = (message: ArchivedMessage, previous?: ArchivedMessage, inPanel = false) => {
    const mine = message.senderId === userId;
    const showSender = (conversation.kind !== 'DIRECT' || inPanel) && !mine && message.kind === 'text'
      && (previous?.senderId !== message.senderId || previous?.kind !== 'text');
    return (
      <MessageRow
        key={message.id}
        message={message}
        mine={mine}
        status={statusOf(message)}
        userId={userId}
        people={everyone}
        senderName={showSender ? personName(message.senderId) : null}
        nameOf={personName}
        replied={message.replyTo ? byId.get(message.replyTo) ?? null : undefined}
        replies={inPanel ? 0 : repliesTo.get(message.id)?.length ?? 0}
        onOpenThread={dock || inPanel || message.kind !== 'text' ? undefined : (target) => {
          setDetails(false);
          setPanelRoot(target.id);
        }}
        onReply={(target) => setDraft({ mode: 'reply', message: target })}
        onEdit={(target) => setDraft({ mode: 'edit', message: target })}
        onDelete={(target) => void remove(target.id)}
        onReact={(target, emoji, on) => void react(target.id, emoji, on)}
      />
    );
  };

  let lastDay = '';
  const shown = dock ? messages : messages.filter((message) => !message.threadId || !byId.has(message.threadId));
  const rows = shown.map((message: ArchivedMessage, index) => {
    const day = new Date(message.sentAt).toLocaleDateString(i18n.language, { dateStyle: 'medium' });
    const separator = day !== lastDay ? <li key={`day-${day}`} className="message-day"><span>{day}</span></li> : null;
    lastDay = day;
    return [separator, renderMessage(message, shown[index - 1])];
  });

  return (
    <div className={`thread-layout${(details || root) && !dock ? ' thread-layout--details' : ''}`}>
      <section className={`thread${dock ? ' thread--dock' : ''}`} aria-label={title}>
        {!dock && (
          <header className="thread-head">
            <button type="button" className="btn-ghost thread-back" aria-label={t('messages.back')} onClick={() => select(null)}>
              <Icon name="chevron-left" />
            </button>
            <div className="thread-title">
              <h2>{title}</h2>
              <span className="thread-subtitle">
                <Icon name="lock" size="sm" />
                {conversation.kind === 'DIRECT'
                  ? t('messages.thread.encrypted')
                  : t(conversation.kind === 'BOARD' ? 'messages.thread.boardMembers' : 'messages.thread.members',
                    { count: conversation.members.length })}
                {conversation.disappearAfter ? (
                  <span className="thread-disappear"
                    title={t('messages.disappear.title', { after: disappearLabel(conversation.disappearAfter, t) })}>
                    <Icon name="clock" size="sm" />
                    {disappearLabel(conversation.disappearAfter, t)}
                  </span>
                ) : null}
              </span>
            </div>
            <button type="button" className="btn-ghost" aria-expanded={details} aria-label={t('messages.details.open')}
              onClick={() => { setPanelRoot(null); setDetails((open) => !open); }}>
              <Icon name="info" />
            </button>
          </header>
        )}

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

        <EarlierMessages conversation={conversation} userId={userId} />
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
        {readOnly ? (
          <p className="thread-readonly" role="note">{t('messages.thread.readOnly')}</p>
        ) : (
          <Composer
            disabled={blocked}
            draft={draft}
            draftAuthor={draft ? personName(draft.message.senderId) : ''}
            people={mentionable}
            attachments
            onCancelDraft={() => setDraft(null)}
            onSend={onSend}
            onTyping={notifyTyping}
          />
        )}
      </section>
      {details && !dock && <ConversationDetails onClose={() => setDetails(false)} />}
      {root && !dock && (
        <ThreadPanel root={root} replies={repliesTo.get(root.id) ?? []} people={mentionable} readOnly={readOnly}
          disabled={blocked} renderMessage={(message) => renderMessage(message, undefined, true)}
          onClose={() => setPanelRoot(null)} />
      )}
    </div>
  );
}
