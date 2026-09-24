import React, { useLayoutEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import '../styles/DemoBanner.css';

const DEMO_HOSTNAME_MARKER = 'kanbanproject';
const HEIGHT_PROPERTY = '--demo-banner-height';

const TEST_ACCOUNTS = [
  { email: 'sfk31231@laoia.com', password: 'test1234' },
  { email: 'lvo69372@laoia.com', password: 'test1234' },
];

const DemoBanner = () => {
  const [dismissed, setDismissed] = useState(false);
  const { t } = useTranslation();
  const bannerRef = useRef(null);

  const isDemoHost = typeof window !== 'undefined'
    && window.location.hostname.includes(DEMO_HOSTNAME_MARKER);
  const shown = isDemoHost && !dismissed;

  useLayoutEffect(() => {
    const banner = bannerRef.current;
    if (!shown || !banner) return undefined;

    const root = document.documentElement;
    const publish = () => root.style.setProperty(HEIGHT_PROPERTY, `${banner.offsetHeight}px`);
    publish();

    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(publish);
    observer?.observe(banner);
    return () => {
      observer?.disconnect();
      root.style.removeProperty(HEIGHT_PROPERTY);
    };
  }, [shown]);

  if (!shown) return null;

  return (
    <div className="demo-banner" role="status" ref={bannerRef}>
      <span className="demo-banner-text">
        {t('demo.notice')}{' '}
        {TEST_ACCOUNTS.map(({ email, password }, index) => (
          <React.Fragment key={email}>
            {index > 0 && <> {t('demo.or')} </>}
            <strong>{email}</strong> / <strong>{password}</strong>
          </React.Fragment>
        ))}
      </span>
      <button
        type="button"
        className="demo-banner-close"
        onClick={() => setDismissed(true)}
        aria-label={t('demo.dismiss')}
      >
        ×
      </button>
    </div>
  );
};

export default DemoBanner;
