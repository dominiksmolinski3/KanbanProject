import { useContext } from 'react';
import { useTranslation } from 'react-i18next';
import { useChat } from '../context/ChatContext';
import KanbanContext from '../context/KanbanContext';
import Icon from './Icon';
import ChatComposer from './chat/ChatComposer';
import ChatControls from './chat/ChatControls';
import ChatMessages from './chat/ChatMessages';
import '../styles/components/Chat.css';

function Chat() {
  const { isOpen, unreadCount, messageType, toggleChat } = useChat();
  const kanban = useContext(KanbanContext);
  const { t } = useTranslation();

  const boardName = kanban?.activeBoard?.name;

  if (!isOpen) {
    return (
      <div className="chat-container">
        <button
          type="button"
          className="chat-toggle-button"
          onClick={toggleChat}
          aria-label={t('chat.openChat')}
          title={t('chat.openChat')}
        >
          <Icon name="comment" size="lg" />
          {unreadCount > 0 && <span className="unread-badge">{unreadCount}</span>}
        </button>
      </div>
    );
  }

  return (
    <div className="chat-container">
      <section className="chat-panel" aria-labelledby="chat-title">
        <div className="chat-header">
          <div className="chat-heading">
            <h3 id="chat-title">
              {messageType === 'private' ? t('chat.private') : t('chat.boardConversation')}
            </h3>
            {boardName && <span className="chat-board-name">{boardName}</span>}
          </div>
          <button
            type="button"
            onClick={toggleChat}
            className="close-chat-btn"
            aria-label={t('chat.closeChat')}
            title={t('chat.closeChat')}
          >
            <Icon name="close" />
          </button>
        </div>

        <ChatControls />
        <ChatMessages />
        <ChatComposer />
      </section>
    </div>
  );
}

export default Chat;
