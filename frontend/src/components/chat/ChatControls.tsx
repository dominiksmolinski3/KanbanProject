import { useTranslation } from 'react-i18next';
import { useChat } from '../../context/ChatContext';
import Icon from '../Icon';

export default function ChatControls() {
  const { messageType, recipient, setMessageType, setRecipient, reloadConversation } = useChat();
  const { t } = useTranslation();
  const isPrivate = messageType === 'private';

  return (
    <div className="chat-controls">
      <div className="chat-kind" role="group" aria-label={t('chat.conversationKind')}>
        <button
          type="button"
          className="chat-kind-option"
          aria-pressed={!isPrivate}
          onClick={() => setMessageType('board')}
        >
          {t('chat.board')}
        </button>
        <button
          type="button"
          className="chat-kind-option"
          aria-pressed={isPrivate}
          onClick={() => setMessageType('private')}
        >
          <Icon name="lock" size="sm" />
          {t('chat.private')}
        </button>
      </div>

      {isPrivate && (
        <input
          type="text"
          placeholder={t('chat.recipient')}
          aria-label={t('chat.recipient')}
          value={recipient}
          onChange={(e) => setRecipient(e.target.value)}
          onBlur={reloadConversation}
          className="recipient-input"
        />
      )}
    </div>
  );
}
