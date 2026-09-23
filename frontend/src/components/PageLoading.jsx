import React from 'react';
import { useTranslation } from 'react-i18next';

function PageLoading() {
  const { t } = useTranslation();
  return <div>{t('app.loading')}</div>;
}

export default PageLoading;
