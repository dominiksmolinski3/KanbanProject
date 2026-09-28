import React, { useState } from 'react';
import { toast } from 'react-toastify';
import { useTranslation } from 'react-i18next';
import { authService } from '../services/authService';
import { useAuth } from '../context/AuthContext';
import '../styles/components/Account.css';

const ERROR_KEYS = {
  WRONG_PASSWORD: 'account.errors.wrongPassword',
  EMAIL_UNCHANGED: 'account.errors.unchanged',
  INVALID_VERIFICATION_CODE: 'account.errors.invalidCode',
  VERIFICATION_CODE_EXPIRED: 'account.errors.expiredCode',
  VALIDATION_ERROR: 'account.errors.invalid',
};

function EmailChange({ user, onChanged }) {
  const { t } = useTranslation();
  const [newEmail, setNewEmail] = useState('');
  const [password, setPassword] = useState('');
  const [sentTo, setSentTo] = useState(null);
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);

  const fail = (error) => toast.error(t(ERROR_KEYS[error.code] || 'account.errors.generic'));

  const request = async (event) => {
    event.preventDefault();
    setBusy(true);
    try {
      await authService.requestEmailChange(user.id, newEmail, password);
      setSentTo(newEmail.trim());
      setPassword('');
    } catch (error) {
      fail(error);
    } finally {
      setBusy(false);
    }
  };

  const confirm = async (event) => {
    event.preventDefault();
    setBusy(true);
    try {
      const session = await authService.confirmEmailChange(user.id, code);
      onChanged(session);
      toast.success(t('account.email.changed'));
      setSentTo(null);
      setNewEmail('');
      setCode('');
    } catch (error) {
      fail(error);
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="account-card" aria-labelledby="account-email-heading">
      <h2 id="account-email-heading">{t('account.email.heading')}</h2>
      <p className="account-muted">{t('account.email.current', { email: user.email })}</p>

      {sentTo === null ? (
        <form className="account-form" onSubmit={request}>
          <label htmlFor="account-new-email">{t('account.email.newLabel')}</label>
          <input
            id="account-new-email"
            type="email"
            autoComplete="email"
            required
            maxLength={255}
            value={newEmail}
            onChange={(event) => setNewEmail(event.target.value)}
          />
          <label htmlFor="account-email-password">{t('account.password.currentLabel')}</label>
          <input
            id="account-email-password"
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />
          <button type="submit" className="account-submit" disabled={busy}>
            {t('account.email.send')}
          </button>
        </form>
      ) : (
        <form className="account-form" onSubmit={confirm}>
          <p role="status">{t('account.email.sent', { email: sentTo })}</p>
          <label htmlFor="account-email-code">{t('account.email.codeLabel')}</label>
          <input
            id="account-email-code"
            inputMode="numeric"
            autoComplete="one-time-code"
            pattern="\d{6}"
            maxLength={6}
            required
            value={code}
            onChange={(event) => setCode(event.target.value.replace(/\D/g, ''))}
          />
          <div className="account-actions">
            <button type="submit" className="account-submit" disabled={busy}>
              {t('account.email.confirm')}
            </button>
            <button type="button" className="account-secondary" onClick={() => setSentTo(null)}>
              {t('account.email.cancel')}
            </button>
          </div>
        </form>
      )}
    </section>
  );
}

function PasswordChange({ user, onChanged }) {
  const { t } = useTranslation();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async (event) => {
    event.preventDefault();
    setBusy(true);
    try {
      await authService.changePassword(user.id, current, next);
      toast.success(t('account.password.changed'));
      onChanged();
    } catch (error) {
      toast.error(t(ERROR_KEYS[error.code] || 'account.errors.generic'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="account-card" aria-labelledby="account-password-heading">
      <h2 id="account-password-heading">{t('account.password.heading')}</h2>
      <p className="account-muted">{t('account.password.intro')}</p>
      <form className="account-form" onSubmit={submit}>
        <label htmlFor="account-current-password">{t('account.password.currentLabel')}</label>
        <input
          id="account-current-password"
          type="password"
          autoComplete="current-password"
          required
          value={current}
          onChange={(event) => setCurrent(event.target.value)}
        />
        <label htmlFor="account-new-password">{t('account.password.newLabel')}</label>
        <input
          id="account-new-password"
          type="password"
          autoComplete="new-password"
          required
          minLength={8}
          maxLength={72}
          value={next}
          onChange={(event) => setNext(event.target.value)}
        />
        <button type="submit" className="account-submit" disabled={busy}>
          {t('account.password.submit')}
        </button>
      </form>
    </section>
  );
}

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
    <div className="container account-page">
      <h1>{t('account.title')}</h1>
      <EmailChange user={user} onChanged={login} />
      <PasswordChange user={user} onChanged={signOutAfterPasswordChange} />
    </div>
  );
}

export default Account;
