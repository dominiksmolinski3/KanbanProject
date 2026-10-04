import React, { useState } from 'react';
import { toast } from 'react-toastify';
import { useTranslation } from 'react-i18next';
import { authService } from '../services/authService';
import { Link } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { getUserAvatar, uploadUserAvatar } from '../services/api';
import useUserAvatar, { clearAvatarCache } from '../board/useUserAvatar';
import { hueOf, initialsOf } from '../board/cardModel';
import Icon from './Icon';
import '../styles/components/Account.css';

const AVATAR_MAX_BYTES = 1024 * 1024;
const AVATAR_TYPES = ['image/jpeg', 'image/png', 'image/webp', 'image/gif'];

function PasswordInput({ id, autoComplete, value, onChange, minLength, maxLength }) {
  const { t } = useTranslation();
  const [shown, setShown] = useState(false);
  return (
    <span className="account-password">
      <input
        id={id}
        className="field-input"
        type={shown ? 'text' : 'password'}
        autoComplete={autoComplete}
        required
        minLength={minLength}
        maxLength={maxLength}
        value={value}
        onChange={onChange}
      />
      <button
        type="button"
        className="account-reveal"
        aria-pressed={shown}
        aria-label={shown ? t('auth.hidePassword') : t('auth.showPassword')}
        title={shown ? t('auth.hidePassword') : t('auth.showPassword')}
        onClick={() => setShown(!shown)}
      >
        <Icon name="eye" size="sm" />
      </button>
    </span>
  );
}

function Profile({ user }) {
  const { t } = useTranslation();
  const stored = useUserAvatar(user.id);
  const [preview, setPreview] = useState(null);
  const [busy, setBusy] = useState(false);
  const url = preview || stored;

  const upload = async (event) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;
    if (!AVATAR_TYPES.includes(file.type)) {
      toast.info(t('usersManagement.messages.fileTypeError'));
      return;
    }
    if (file.size > AVATAR_MAX_BYTES) {
      toast.info(t('usersManagement.messages.fileTooLarge'));
      return;
    }
    setBusy(true);
    try {
      await uploadUserAvatar(user.id, file);
      clearAvatarCache();
      setPreview(await getUserAvatar(user.id));
      toast.success(t('usersManagement.messages.avatarUpdated'));
    } catch (error) {
      toast.error(error.message || t('account.errors.generic'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="page-panel account-profile">
      {url ? (
        <img className="account-avatar" src={url} alt="" />
      ) : (
        <span className="account-avatar" aria-hidden="true" style={{ '--avatar-hue': hueOf(user.id) }}>
          {initialsOf(user.name || user.email)}
        </span>
      )}
      <div className="account-identity">
        <strong>{user.name || user.email}</strong>
        <span>{user.email}</span>
      </div>
      <label className={`btn btn-secondary btn-sm account-avatar-change${busy ? ' busy' : ''}`}>
        {t('account.avatar.change')}
        <input
          type="file"
          accept={AVATAR_TYPES.join(',')}
          disabled={busy}
          onChange={upload}
        />
      </label>
    </section>
  );
}

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
    <section className="page-panel account-card" aria-labelledby="account-email-heading">
      <div className="account-card-head">
        <h2 className="page-panel-title" id="account-email-heading">{t('account.email.heading')}</h2>
        <p className="account-muted">{t('account.email.current', { email: user.email })}</p>
      </div>

      {sentTo === null ? (
        <form className="account-form" onSubmit={request}>
          <label className="field-label" htmlFor="account-new-email">{t('account.email.newLabel')}</label>
          <input
            id="account-new-email"
            className="field-input"
            type="email"
            autoComplete="email"
            required
            maxLength={255}
            value={newEmail}
            onChange={(event) => setNewEmail(event.target.value)}
          />
          <label className="field-label" htmlFor="account-email-password">{t('account.password.currentLabel')}</label>
          <PasswordInput
            id="account-email-password"
            autoComplete="current-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />
          <div className="account-actions">
            <button type="submit" className="btn btn-primary account-submit" disabled={busy}>
              {t('account.email.send')}
            </button>
          </div>
        </form>
      ) : (
        <form className="account-form" onSubmit={confirm}>
          <p className="account-sent" role="status">
            <Icon name="mail" size="sm" />
            {t('account.email.sent', { email: sentTo })}
          </p>
          <label className="field-label" htmlFor="account-email-code">{t('account.email.codeLabel')}</label>
          <input
            id="account-email-code"
            className="field-input account-code"
            dir="ltr"
            inputMode="numeric"
            autoComplete="one-time-code"
            pattern="\d{6}"
            maxLength={6}
            required
            value={code}
            onChange={(event) => setCode(event.target.value.replace(/\D/g, ''))}
          />
          <div className="account-actions">
            <button type="submit" className="btn btn-primary account-submit" disabled={busy}>
              {t('account.email.confirm')}
            </button>
            <button type="button" className="btn btn-secondary account-secondary" onClick={() => setSentTo(null)}>
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
    <section className="page-panel account-card" aria-labelledby="account-password-heading">
      <div className="account-card-head">
        <h2 className="page-panel-title" id="account-password-heading">{t('account.password.heading')}</h2>
        <p className="account-muted">{t('account.password.intro')}</p>
      </div>
      <form className="account-form" onSubmit={submit}>
        <label className="field-label" htmlFor="account-current-password">{t('account.password.currentLabel')}</label>
        <PasswordInput
          id="account-current-password"
          autoComplete="current-password"
          value={current}
          onChange={(event) => setCurrent(event.target.value)}
        />
        <label className="field-label" htmlFor="account-new-password">{t('account.password.newLabel')}</label>
        <PasswordInput
          id="account-new-password"
          autoComplete="new-password"
          minLength={8}
          maxLength={72}
          value={next}
          onChange={(event) => setNext(event.target.value)}
        />
        <div className="account-actions">
          <button type="submit" className="btn btn-primary account-submit" disabled={busy}>
            {t('account.password.submit')}
          </button>
        </div>
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
