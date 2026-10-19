import type { CSSProperties } from 'react';
import { useTranslation } from 'react-i18next';
import { hueOf, initialsOf } from '../../board/cardModel';
import type { ChatEntry } from '../../context/ChatContext';
import Icon from '../Icon';

type Kind = 'system-message' | 'own-message' | 'other-message';

function kindOf(msg: ChatEntry, ownEmail: string | undefined): Kind {
  if (msg.type === 'JOIN' || msg.type === 'LEAVE') {
    return 'system-message';
  }
  return msg.sender === ownEmail ? 'own-message' : 'other-message';
}

const formatTimestamp = (timestamp: string) =>
  timestamp ? new Date(timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '';

export default function ChatMessageRow({ msg, ownEmail }: { msg: ChatEntry; ownEmail: string | undefined }) {
  const { t } = useTranslation();
  const kind = kindOf(msg, ownEmail);
  const isPrivate = msg.type === 'PRIVATE' || msg.recipientId;

  return (
    <div className={`chat-message ${kind}${isPrivate ? ' private-message' : ''}`}>
      {msg.type === 'JOIN' && <div className="system-content">{t('chat.userJoined', { user: msg.sender })}</div>}
      {msg.type === 'LEAVE' && <div className="system-content">{t('chat.userLeft', { user: msg.sender })}</div>}

      {(msg.type === 'CHAT' || msg.type === 'PRIVATE') && (
        <>
          {kind === 'other-message' && (
            <span
              className="message-avatar"
              aria-hidden="true"
              style={{ '--avatar-hue': hueOf(msg.sender || '') } as CSSProperties}
            >
              {initialsOf(msg.sender || '')}
            </span>
          )}
          <div className="message-main">
            <div className="message-header">
              {kind === 'other-message' && <span className="message-sender">{msg.sender}</span>}
              {msg.recipientId && (
                <span className="message-private">
                  <Icon name="lock" size="sm" />
                  {msg.recipientId}
                </span>
              )}
              <span className="message-time">{formatTimestamp(msg.timestamp)}</span>
            </div>
            <div className="message-content">{msg.content}</div>
          </div>
        </>
      )}
    </div>
  );
}
