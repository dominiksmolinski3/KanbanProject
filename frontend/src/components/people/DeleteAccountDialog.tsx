import { useState, type FormEvent } from 'react';
import { toast } from 'react-toastify';
import { useTranslation } from 'react-i18next';
import { useAsyncAction } from '../../hooks/useAsyncAction';
import FormModal from '../FormModal';
import PasswordInput from '../account/PasswordInput';
import { failureText } from '../account/failureText';
import '../../styles/components/Account.css';

interface DeleteAccountDialogProps {
  onConfirm: (currentPassword: string) => Promise<void>;
  onCancel: () => void;
}

export default function DeleteAccountDialog({ onConfirm, onCancel }: DeleteAccountDialogProps) {
  const { t } = useTranslation();
  const [password, setPassword] = useState('');
  const { pending, run } = useAsyncAction({ onError: (error) => toast.error(failureText(t, error)) });

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    await run(() => onConfirm(password));
  };

  return (
    <FormModal onClose={onCancel} ariaLabel={t('usersManagement.buttons.delete')}>
      <form className="account-form" onSubmit={submit}>
        <p>{t('usersManagement.messages.deleteConfirm')}</p>
        <label className="field-label" htmlFor="delete-account-password">{t('account.password.currentLabel')}</label>
        <PasswordInput
          id="delete-account-password"
          autoComplete="current-password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
        />
        <div className="account-actions">
          <button type="button" className="btn btn-secondary" onClick={onCancel} disabled={pending}>
            {t('account.email.cancel')}
          </button>
          <button type="submit" className="btn btn-danger" disabled={pending}>
            {t('usersManagement.buttons.delete')}
          </button>
        </div>
      </form>
    </FormModal>
  );
}
