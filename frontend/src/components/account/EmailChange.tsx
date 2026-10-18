import { useState, type FormEvent } from 'react';
import { toast } from 'react-toastify';
import { useTranslation } from 'react-i18next';
import { authService } from '../../services/authService';
import type { StoredSession } from '../../services/session';
import { useAsyncAction } from '../../hooks/useAsyncAction';
import type { User } from '../../types/api';
import Icon from '../Icon';
import PasswordInput from './PasswordInput';
import { failureText } from './failureText';

interface EmailChangeProps {
  user: User;
  onChanged: (session: StoredSession) => void;
}

export default function EmailChange({ user, onChanged }: EmailChangeProps) {
  const { t } = useTranslation();
  const [newEmail, setNewEmail] = useState('');
  const [password, setPassword] = useState('');
  const [sentTo, setSentTo] = useState<string | null>(null);
  const [code, setCode] = useState('');
  const { pending: busy, run } = useAsyncAction({ onError: (error) => toast.error(failureText(t, error)) });

  const request = async (event: FormEvent) => {
    event.preventDefault();
    await run(async () => {
      await authService.requestEmailChange(user.id, newEmail, password);
      setSentTo(newEmail.trim());
      setPassword('');
    });
  };

  const confirm = async (event: FormEvent) => {
    event.preventDefault();
    await run(async () => {
      const session = await authService.confirmEmailChange(user.id, code);
      onChanged(session);
      toast.success(t('account.email.changed'));
      setSentTo(null);
      setNewEmail('');
      setCode('');
    });
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
