import React, { useState } from 'react';
import '../styles/DemoBanner.css';

// Cosmetic-only marker: this banner exists to tell visitors to the public demo
// deployment (kanbanproject.pl) that it is a test site, and to hand them a
// working login. It has nothing to do with authentication or access control.
const DEMO_HOSTNAME_MARKER = 'kanbanproject';

const TEST_ACCOUNTS = [
  { email: 'sfk31231@laoia.com', password: 'test1234' },
  { email: 'lvo69372@laoia.com', password: 'test1234' },
];

const DemoBanner = () => {
  const [dismissed, setDismissed] = useState(false);

  const isDemoHost = typeof window !== 'undefined'
    && window.location.hostname.includes(DEMO_HOSTNAME_MARKER);

  if (!isDemoHost || dismissed) return null;

  return (
    <div className="demo-banner" role="status">
      <span className="demo-banner-text">
        This is a test website. Sign in with a demo account:{' '}
        {TEST_ACCOUNTS.map(({ email, password }, index) => (
          <React.Fragment key={email}>
            {index > 0 && ' or '}
            <strong>{email}</strong> / <strong>{password}</strong>
          </React.Fragment>
        ))}
      </span>
      <button
        type="button"
        className="demo-banner-close"
        onClick={() => setDismissed(true)}
        aria-label="Dismiss"
      >
        ×
      </button>
    </div>
  );
};

export default DemoBanner;
