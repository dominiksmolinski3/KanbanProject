import { useFormContext } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import type { AuthFields } from './authFields';

interface PasswordFieldProps {
  id: string;
  name: 'password' | 'confirmPassword';
  label: string;
  placeholder: string;
  visible: boolean;
  onToggle?: () => void;
}

const EyeIcon = ({ crossed }: { crossed: boolean }) => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
    <circle cx="12" cy="12" r="3" />
    {crossed && <line x1="2" y1="2" x2="22" y2="22" />}
  </svg>
);

export default function PasswordField({ id, name, label, placeholder, visible, onToggle }: PasswordFieldProps) {
  const { register } = useFormContext<AuthFields>();
  const { t } = useTranslation();

  return (
    <div className="form-group password-field">
      <label className="field-label" htmlFor={id}>{label}</label>
      <input
        id={id}
        type={visible ? 'text' : 'password'}
        placeholder={placeholder}
        required
        {...register(name)}
      />
      {onToggle && (
        <button
          type="button"
          className="password-toggle"
          onClick={onToggle}
          aria-label={visible ? t('auth.hidePassword', 'Hide password') : t('auth.showPassword', 'Show password')}
        >
          <EyeIcon crossed={visible} />
        </button>
      )}
    </div>
  );
}
