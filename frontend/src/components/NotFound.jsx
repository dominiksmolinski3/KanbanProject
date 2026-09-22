import React from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';

// nginx's try_files answers every unknown path with the shell, so a mistyped route (e.g.
// /boards instead of /board) renders the app with no matched <Route> and nothing to say so.
function NotFound() {
  const { t } = useTranslation();

  return (
    <div className="not-found-page">
      <h1>{t('notFound.title')}</h1>
      <p>{t('notFound.message')}</p>
      <Link to="/">{t('notFound.backHome')}</Link>
    </div>
  );
}

export default NotFound;
