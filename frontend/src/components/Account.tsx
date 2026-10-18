import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import Icon from './Icon';
import EmailChange from './account/EmailChange';
import PasswordChange from './account/PasswordChange';
import Profile from './account/Profile';
import '../styles/components/Account.css';

function Account() {
  const { t } = useTranslation();
  const { user, login, logout } = useAuth();

  if (!user) {
    return null;
  }

  // The server ends every session on a password change, so this one cannot be renewed either.
  const signOutAfterPasswordChange = async () => {
    await logout();
    window.location.href = '/';
  };

  return (
    <div className="page-shell account-page">
      <div className="page-head">
        <h1 className="page-title">{t('account.title')}</h1>
      </div>
      <Profile user={user} />
      <EmailChange user={user} onChanged={login} />
      <PasswordChange user={user} onChanged={signOutAfterPasswordChange} />
      <Link to="/sessions" className="page-panel account-sessions-link">
        <span className="account-sessions-icon"><Icon name="laptop" /></span>
        <span className="account-sessions-text">
          <strong>{t('devices.title')}</strong>
          <span>{t('devices.intro')}</span>
        </span>
        <Icon name="chevron-right" size="sm" />
      </Link>
    </div>
  );
}

export default Account;
