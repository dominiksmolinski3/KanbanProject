import { useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { ArchivedMessage } from '../../crypto/engine';
import { useOutsideClick } from '../../hooks/useOutsideClick';
import Icon from '../Icon';
import { REACTIONS } from './reactions';

interface MessageRowProps {
  message: ArchivedMessage;
  mine: boolean;
  senderName: string | null;
  nameOf: (userId: number | null) => string;
  replied: ArchivedMessage | null | undefined;
  userId: number | null;
  onReply: (message: ArchivedMessage) => void;
  onEdit: (message: ArchivedMessage) => void;
  onDelete: (message: ArchivedMessage) => void;
  onReact: (message: ArchivedMessage, emoji: string, on: boolean) => void;
}

export default function MessageRow({ message, mine, senderName, nameOf, replied, userId, onReply, onEdit, onDelete, onReact }: MessageRowProps) {
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

  return (
    <li className={`message-row${mine ? ' is-mine' : ''}`}>
      <div className="message-bubble">
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
          : <p className="message-text">{message.text}</p>}
        <span className="message-meta">
          <time dateTime={message.sentAt}>{time}</time>
          {message.editedAt && !message.deleted && <span>{t('messages.message.edited')}</span>}
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
