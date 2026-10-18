import { useState, type FormEvent } from 'react';
import { toast } from 'react-toastify';
import { useTranslation } from 'react-i18next';
import { authService } from '../../services/authService';
import { useAsyncAction } from '../../hooks/useAsyncAction';
import type { User } from '../../types/api';
import PasswordInput from './PasswordInput';
import { failureText } from './failureText';

interface PasswordChangeProps {
  user: User;
  onChanged: () => void;
}

export default function PasswordChange({ user, onChanged }: PasswordChangeProps) {
  const { t } = useTranslation();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const { pending: busy, run } = useAsyncAction({ onError: (error) => toast.error(failureText(t, error)) });

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    await run(async () => {
      await authService.changePassword(user.id, current, next);
      toast.success(t('account.password.changed'));
      onChanged();
    });
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
