import { useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../context/AuthContext';
import { useChat } from '../../context/ChatContext';
import Icon from '../Icon';
import ChatMessageRow from './ChatMessageRow';

export default function ChatMessages() {
  const { messages, hasMoreHistory, isLoadingHistory, loadOlderMessages } = useChat();
  const { user } = useAuth();
  const { t } = useTranslation();
  const end = useRef<HTMLDivElement>(null);

  useEffect(() => {
    end.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  return (
    <div className="chat-messages" role="log" aria-live="polite">
      {hasMoreHistory && (
        <button type="button" className="load-older-btn" onClick={loadOlderMessages} disabled={isLoadingHistory}>
          {isLoadingHistory ? t('chat.loadingHistory') : t('chat.loadOlder')}
        </button>
      )}

      {messages.length === 0 ? (
        <div className="no-messages">
          <Icon name="comment" size="lg" />
          <p>{t('chat.noMessages')}</p>
        </div>
      ) : (
        messages.map((msg) => <ChatMessageRow key={msg.id} msg={msg} ownEmail={user?.email} />)
      )}
      <div ref={end} />
    </div>
  );
}
