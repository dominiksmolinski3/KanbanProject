import React, { useRef, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { useChat } from '../context/ChatContext';
import { useAuth } from '../context/AuthContext';
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

  return (
    <div className="chat-container">
      {isOpen ? (
        <div className="chat-panel">
          <div className="chat-header">
            <h3>
              {messageType === 'private' ? t('chat.private') : t('chat.boardConversation')}
            </h3>
            <div className="chat-controls">
              <select
                value={messageType}
                onChange={(e) => setMessageType(e.target.value)}
                className="message-type-select"
                aria-label={t('chat.conversationKind')}
              >
                <option value="board">{t('chat.board')}</option>
                <option value="private">{t('chat.private')}</option>
              </select>

              {messageType === 'private' && (
                <input
                  type="text"
                  placeholder={t('chat.recipient')}
                  value={recipient}
                  onChange={(e) => setRecipient(e.target.value)}
                  onBlur={reloadConversation}
                  className="recipient-input"
                />
              )}

              <button
                onClick={toggleChat}
                className="close-chat-btn"
                title={t('chat.closeChat')}
              >
                ×
              </button>
            </div>
          </div>

          <div className="chat-messages" ref={chatContainerRef}>
            {hasMoreHistory && (
              <button
                className="load-older-btn"
                onClick={loadOlderMessages}
                disabled={isLoadingHistory}
              >
                {isLoadingHistory ? t('chat.loadingHistory') : t('chat.loadOlder')}
              </button>
            )}

            {messages.length === 0 ? (
              <div className="no-messages">{t('chat.noMessages')}</div>
            ) : (
              messages.map(msg => (
                <div key={msg.id} className={`chat-message ${getMessageClass(msg)}`}>
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
                      <div className="message-header">
                        <span className="message-sender">
                          {msg.sender}
                          {msg.recipientId && <span className="message-private"> → {msg.recipientId}</span>}
                        </span>
                        <span className="message-time">{formatTimestamp(msg.timestamp)}</span>
                      </div>
                      <div className="message-content">{msg.content}</div>
                    </>
                )}
                </div>
              ))
            )}
            <div ref={messagesEndRef} />
          </div>

          {boardConversationIsReadOnly && (
            <p className="chat-readonly-note">{t('chat.errors.readOnly')}</p>
          )}
          <div className="chat-input-container">
            <textarea
              value={message}
              onChange={(e) => setMessage(e.target.value)}
              onKeyPress={handleKeyPress}
              placeholder={t('chat.typingMessage')}
              aria-label={t('chat.typingMessage')}
              className="chat-input"
              disabled={boardConversationIsReadOnly}
            />
            <button
              onClick={sendMessage}
              disabled={!isConnected || !message.trim() || boardConversationIsReadOnly}
              className="send-button"
            >
              {t('chat.send')}
            </button>
          </div>
        </div>
      ) : (
        <button
          className="chat-toggle-button"
          onClick={toggleChat}
          title={t('chat.openChat')}
        >
          <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M17 8h2a2 2 0 012 2v6a2 2 0 01-2 2h-2v4l-4-4H9a1.994 1.994 0 01-1.414-.586m0 0L11 14h4a2 2 0 002-2V6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2v4l.586-.586z" />
          </svg>
          {unreadCount > 0 && <span className="unread-badge">{unreadCount}</span>}
        </button>
      )}
    </div>
  );
}

export default Chat;
