import React, { useRef, useEffect, useContext } from 'react';
import { useTranslation } from 'react-i18next';
import { useChat } from '../context/ChatContext';
import { useAuth } from '../context/AuthContext';
import KanbanContext from '../context/KanbanContext';
import { hueOf, initialsOf } from '../board/cardModel';
import Icon from './Icon';
import '../styles/components/Chat.css';

function Chat() {
  const {
    isOpen,
    messages,
    message,
    isConnected,
    unreadCount,
    recipient,
    messageType,
    hasMoreHistory,
    isLoadingHistory,
    isBoardReadOnly,
    toggleChat,
    sendMessage,
    loadOlderMessages,
    reloadConversation,
    setMessage,
    setMessageType,
    setRecipient
  } = useChat();

  const boardConversationIsReadOnly = messageType !== 'private' && isBoardReadOnly;

  const { user } = useAuth();
  const kanban = useContext(KanbanContext);
  const { t } = useTranslation();

  const messagesEndRef = useRef(null);
  const chatContainerRef = useRef(null);

  useEffect(() => {
    if (isOpen && messagesEndRef.current) {
      messagesEndRef.current.scrollIntoView({ behavior: 'smooth' });
    }
  }, [messages, isOpen]);

  const handleKeyPress = (e) => {
    if (boardConversationIsReadOnly) {
      return;
    }
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      sendMessage();
    }
  };

  const getMessageClass = (msg) => {
    if (msg.type === 'JOIN' || msg.type === 'LEAVE') {
      return 'system-message';
    }

    if (msg.sender === user?.email) {
      return 'own-message';
    }

    return 'other-message';
  };

  const formatTimestamp = (timestamp) => {
    if (!timestamp) return '';
    const date = new Date(timestamp);
    return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  };

  const boardName = kanban?.activeBoard?.name;
  const isPrivate = messageType === 'private';

  return (
    <div className="chat-container">
      {isOpen ? (
        <section className="chat-panel" aria-labelledby="chat-title">
          <div className="chat-header">
            <div className="chat-heading">
              <h3 id="chat-title">
                {isPrivate ? t('chat.private') : t('chat.boardConversation')}
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

          <div className="chat-messages" ref={chatContainerRef} role="log" aria-live="polite">
            {hasMoreHistory && (
              <button
                type="button"
                className="load-older-btn"
                onClick={loadOlderMessages}
                disabled={isLoadingHistory}
              >
                {isLoadingHistory ? t('chat.loadingHistory') : t('chat.loadOlder')}
              </button>
            )}

            {messages.length === 0 ? (
              <div className="no-messages">
                <Icon name="comment" size="lg" />
                <p>{t('chat.noMessages')}</p>
              </div>
            ) : (
              messages.map(msg => {
                const kind = getMessageClass(msg);
                return (
                  <div
                    key={msg.id}
                    className={`chat-message ${kind}${msg.type === 'PRIVATE' || msg.recipientId ? ' private-message' : ''}`}
                  >
                    {msg.type === 'JOIN' && (
                      <div className="system-content">
                        {t('chat.userJoined', { user: msg.sender })}
                      </div>
                    )}

                    {msg.type === 'LEAVE' && (
                      <div className="system-content">
                        {t('chat.userLeft', { user: msg.sender })}
                      </div>
                    )}

                    {(msg.type === 'CHAT' || msg.type === 'PRIVATE') && (
                      <>
                        {kind === 'other-message' && (
                          <span
                            className="message-avatar"
                            aria-hidden="true"
                            style={{ '--avatar-hue': hueOf(msg.sender || '') }}
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
              })
            )}
            <div ref={messagesEndRef} />
          </div>

          <div className="chat-footer">
            {boardConversationIsReadOnly && (
              <p className="chat-readonly-note">
                <Icon name="eye" size="sm" />
                {t('chat.errors.readOnly')}
              </p>
            )}
            {!boardConversationIsReadOnly && (
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
            )}
          </div>
        </section>
      ) : (
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
      )}
    </div>
  );
}

export default Chat;
