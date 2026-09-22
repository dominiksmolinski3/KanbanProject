import React from 'react';
import { useTranslation } from 'react-i18next';

// Suspense fallback for a lazily-loaded route chunk.
function PageLoading() {
  const { t } = useTranslation();
  return <div>{t('app.loading')}</div>;
}

export default PageLoading;
