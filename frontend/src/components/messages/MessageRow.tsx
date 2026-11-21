import { useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { ArchivedMessage } from '../../crypto/engine';
import { useOutsideClick } from '../../hooks/useOutsideClick';
import Icon from '../Icon';
import MessageFiles from './MessageFiles';
import { splitMentions, type Person } from './mentions';
import { REACTIONS } from './reactions';

export type DeliveryStatus = 'sent' | 'delivered' | 'read';

interface MessageRowProps {
  message: ArchivedMessage;
  mine: boolean;
  status?: DeliveryStatus | null;
  senderName: string | null;
  nameOf: (userId: number | null) => string;
  replied: ArchivedMessage | null | undefined;
  userId: number | null;
  people?: Person[];
  replies?: number;
  onOpenThread?: (message: ArchivedMessage) => void;
  onReply: (message: ArchivedMessage) => void;
  onEdit: (message: ArchivedMessage) => void;
  onDelete: (message: ArchivedMessage) => void;
  onReact: (message: ArchivedMessage, emoji: string, on: boolean) => void;
}

export default function MessageRow({ message, mine, status = null, senderName, nameOf, replied, userId, people = [], replies = 0, onOpenThread, onReply, onEdit, onDelete, onReact }: MessageRowProps) {
  const { t, i18n } = useTranslation();
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);
  useOutsideClick(menuRef, menuOpen, () => setMenuOpen(false));

  if (message.kind === 'notice' && message.notice) {
    return (
      <li className="message-notice">
        <Icon name={message.notice.code === 'identity-changed' || message.notice.code === 'log-rewritten' ? 'warning' : 'info'} size="sm" />
        <span>{t(`messages.notice.${message.notice.code}`, { name: nameOf(message.notice.userId) })}</span>
      </li>
    );
  }

  const time = new Date(message.sentAt).toLocaleTimeString(i18n.language, { hour: '2-digit', minute: '2-digit' });
  const act = (action: () => void) => {
    setMenuOpen(false);
    action();
  };

  const mentionable = people.filter((person) => message.mentions?.includes(person.userId));
  const mentionsMe = userId !== null && !mine && Boolean(message.mentions?.includes(userId));
  const parts = splitMentions(message.text, mentionable);

  return (
    <li className={`message-row${mine ? ' is-mine' : ''}`} id={`message-${message.id}`}>
      <div className={`message-bubble${mentionsMe ? ' has-mention' : ''}`}>
        {senderName && <span className="message-sender">{senderName}</span>}
        {replied !== undefined && (
          <blockquote className="message-quote">
            {replied
              ? <><span className="message-sender">{nameOf(replied.senderId)}</span>{replied.deleted ? t('messages.message.deleted') : replied.text}</>
              : t('messages.message.replyMissing')}
          </blockquote>
        )}
        {message.deleted
          ? <p className="message-text message-text--deleted">{t('messages.message.deleted')}</p>
          : (
            <>
              {message.text && (
                <p className="message-text">
                  {parts.some((part) => part.mention)
                    ? parts.map((part, index) => (part.mention
                      ? <mark key={index} className="message-mention">{part.text}</mark>
                      : <span key={index}>{part.text}</span>))
                    : message.text}
                </p>
              )}
              {message.files && message.files.length > 0 && <MessageFiles files={message.files} />}
            </>
          )}
        {onOpenThread && !message.deleted && replies > 0 && (
          <button type="button" className="message-thread-link" onClick={() => onOpenThread(message)}>
            <Icon name="comment" size="sm" />{t('messages.thread.replies', { count: replies })}
          </button>
        )}
        <span className="message-meta">
          {message.expiresAt && !message.deleted && (
            <span className="message-expiry" title={t('messages.message.disappears', { time: new Date(message.expiresAt).toLocaleString(i18n.language) })}>
              <Icon name="clock" size="sm" />
              <span className="visually-hidden">{t('messages.message.disappears', { time: new Date(message.expiresAt).toLocaleString(i18n.language) })}</span>
            </span>
          )}
          <time dateTime={message.sentAt}>{time}</time>
          {message.editedAt && !message.deleted && <span>{t('messages.message.edited')}</span>}
          {status && (
            <span className={`message-status message-status--${status}`} role="img"
              aria-label={t(`messages.status.${status}`)} title={t(`messages.status.${status}`)}>
              <Icon name={status === 'sent' ? 'check' : 'check-double'} size="sm" />
            </span>
          )}
        </span>
        {Object.keys(message.reactions).length > 0 && (
          <div className="message-reactions">
            {Object.entries(message.reactions).map(([emoji, people]) => {
              const reacted = userId !== null && people.includes(userId);
              return (
                <button key={emoji} type="button" className={`message-reaction${reacted ? ' is-mine' : ''}`} aria-pressed={reacted}
                  aria-label={t('messages.message.reaction', { emoji, count: people.length })}
                  onClick={() => onReact(message, emoji, !reacted)}>
                  <span aria-hidden="true">{emoji}</span> <span aria-hidden="true">{people.length}</span>
                </button>
              );
            })}
          </div>
        )}
      </div>
      {!message.deleted && (
        <div className="message-actions" ref={menuRef}>
          <button type="button" className="btn-ghost message-actions-toggle" aria-haspopup="menu" aria-expanded={menuOpen}
            aria-label={t('messages.message.actions')} onClick={() => setMenuOpen((open) => !open)}>
            <Icon name="more" size="sm" />
          </button>
          {menuOpen && (
            <div className="message-menu" role="menu">
              <div className="message-menu-reactions">
                {REACTIONS.map((emoji) => (
                  <button key={emoji} type="button" role="menuitem" aria-label={t('messages.message.react', { emoji })}
                    onClick={() => act(() => onReact(message, emoji, !(userId !== null && message.reactions[emoji]?.includes(userId))))}>
                    {emoji}
                  </button>
                ))}
              </div>
              <button type="button" role="menuitem" onClick={() => act(() => onReply(message))}>
                <Icon name="comment" size="sm" />{t('messages.message.reply')}
              </button>
              {onOpenThread && (
                <button type="button" role="menuitem" onClick={() => act(() => onOpenThread(message))}>
                  <Icon name="comment" size="sm" />{t('messages.message.replyInThread')}
                </button>
              )}
              {mine && (
                <>
                  <button type="button" role="menuitem" onClick={() => act(() => onEdit(message))}>
                    <Icon name="edit" size="sm" />{t('messages.message.edit')}
                  </button>
                  <button type="button" role="menuitem" className="is-danger" onClick={() => act(() => onDelete(message))}>
                    <Icon name="trash" size="sm" />{t('messages.message.delete')}
                  </button>
                </>
              )}
            </div>
          )}
        </div>
      )}
    </li>
  );
}
