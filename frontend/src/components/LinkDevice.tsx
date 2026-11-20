import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import Icon from './Icon';
import PageLoading from './PageLoading';
import MessagingApprove from './devices/MessagingApprove';
import { useMessagingState } from './devices/useMessagingKeys';

export default function LinkDevice() {
  const { t } = useTranslation();
  const { hash } = useLocation();
  const navigate = useNavigate();
  const { state } = useMessagingState();

  if (state === 'loading') return <PageLoading />;
  if (state !== 'ready') {
    return (
      <section className="messages-gate">
        <Icon name="lock" size="lg" />
        <h1>{t('devices.messaging.approve.title')}</h1>
        <p>{t(state === 'off' ? 'messages.gate.off' : 'devices.messaging.approve.notReady')}</p>
        <Link to="/sessions" className="btn btn-primary">{t('messages.gate.open')}</Link>
      </section>
    );
  }
  return <MessagingApprove initialCode={hash.replace(/^#/, '')} onClose={() => navigate('/sessions', { replace: true })} />;
}
