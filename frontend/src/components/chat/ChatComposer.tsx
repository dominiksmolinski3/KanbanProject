import type { KeyboardEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useChat } from '../../context/ChatContext';
import Icon from '../Icon';

export default function ChatComposer() {
  const { message, messageType, isConnected, isBoardReadOnly, sendMessage, setMessage } = useChat();
  const { t } = useTranslation();
  const readOnly = messageType !== 'private' && isBoardReadOnly;

  if (readOnly) {
    return (
      <div className="chat-footer">
        <p className="chat-readonly-note">
          <Icon name="eye" size="sm" />
          {t('chat.errors.readOnly')}
        </p>
      </div>
    );
  }

  const handleKeyPress = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      sendMessage();
    }
  };

  return (
    <div className="chat-footer">
      <div className="chat-input-container">
        <textarea
          value={message}
          onChange={(e) => setMessage(e.target.value)}
          onKeyPress={handleKeyPress}
          placeholder={t('chat.typingMessage')}
          aria-label={t('chat.typingMessage')}
          className="chat-input"
          rows={1}
        />
        <button
          type="button"
          onClick={sendMessage}
          disabled={!isConnected || !message.trim()}
          className="send-button"
          aria-label={t('chat.send')}
          title={t('chat.send')}
        >
          <Icon name="send" />
        </button>
      </div>
    </div>
  );
}
