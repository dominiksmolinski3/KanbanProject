import { Link, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { MessagesProvider, useMessages } from '../context/MessagesContext';
import { useMessagingKeys } from './devices/useMessagingKeys';
import PageLoading from './PageLoading';
import Icon from './Icon';
import ConversationList from './messages/ConversationList';
import Thread from './messages/Thread';
import '../styles/components/Messages.css';

function MessagesLayout() {
  const { t } = useTranslation();
  const { selected } = useMessages();
  return (
    <div className={`messages-page${selected ? ' messages-page--thread' : ''}`}>
      <ConversationList />
      {selected ? <Thread key={selected.id} /> : (
        <section className="messages-empty" aria-label={t('messages.empty.title')}>
          <Icon name="lock" size="lg" />
          <h2>{t('messages.empty.title')}</h2>
          <p>{t('messages.empty.body')}</p>
        </section>
      )}
    </div>
  );
}

function Messages() {
  const { t } = useTranslation();
  const { state } = useMessagingKeys();
  const [params, setParams] = useSearchParams();
  const initial = Number(params.get('c')) || null;

  if (state === 'loading') return <PageLoading />;
  if (state !== 'ready') {
    return (
      <section className="messages-gate">
        <Icon name="lock" size="lg" />
        <h1>{t('messages.title')}</h1>
        {state === 'off' ? <p>{t('messages.gate.off')}</p> : (
          <>
            <p>{t(state === 'elsewhere' ? 'messages.gate.unlock' : 'messages.gate.setUp')}</p>
            <Link to="/sessions" className="btn btn-primary">{t('messages.gate.open')}</Link>
          </>
        )}
      </section>
    );
  }
  return (
    <MessagesProvider
      initialConversationId={initial}
      onSelect={(id) => setParams(id ? { c: String(id) } : {}, { replace: true })}
    >
      <MessagesLayout />
    </MessagesProvider>
  );
}

export default Messages;
