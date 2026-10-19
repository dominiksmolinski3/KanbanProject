import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import Icon from './Icon';
import DeviceItem from './devices/DeviceItem';
import { useDevices } from './devices/useDevices';
import '../styles/components/Devices.css';

function Devices() {
  const { t } = useTranslation();
  const { sessions, isLoading, endingId, isCurrentSession, endSession } = useDevices();

  return (
    <div className="page-shell devices-page">
      <div className="page-head">
        <h1 className="page-title">{t('devices.title')}</h1>
        <p className="page-lede devices-intro">{t('devices.intro')}</p>
      </div>
      {sessions.some((session) => session.redacted) && (
        <p className="devices-demo-notice" role="status">
          <Icon name="eye" size="sm" />
          {t('devices.demoNotice')}
        </p>
      )}

      <section className="page-panel devices-list" aria-label={t('devices.title')}>
        {isLoading && <div className="page-empty devices-empty">{t('devices.messages.loading')}</div>}

        {!isLoading && sessions.length === 0 && (
          <div className="page-empty devices-empty">
            <span className="page-empty-icon"><Icon name="laptop" /></span>
            <p className="page-empty-title">{t('devices.messages.none')}</p>
          </div>
        )}

        {!isLoading && sessions.map((session) => (
          <DeviceItem
            key={session.id}
            session={session}
            isCurrent={isCurrentSession(session)}
            ending={endingId === session.id}
            onEnd={endSession}
          />
        ))}
      </section>

      <Link to="/account" className="devices-back">
        <Icon name="chevron-left" size="sm" />
        {t('header.account')}
      </Link>
    </div>
  );
}

export default Devices;
