import { useState, type ChangeEvent } from 'react';
import { useTranslation } from 'react-i18next';
import Icon from '../Icon';

interface PasswordInputProps {
  id: string;
  autoComplete: string;
  value: string;
  onChange: (event: ChangeEvent<HTMLInputElement>) => void;
  minLength?: number;
  maxLength?: number;
}

export default function PasswordInput({ id, autoComplete, value, onChange, minLength, maxLength }: PasswordInputProps) {
  const { t } = useTranslation();
  const [shown, setShown] = useState(false);
  const label = shown ? t('auth.hidePassword') : t('auth.showPassword');

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
        aria-label={label}
        title={label}
        onClick={() => setShown(!shown)}
      >
        <Icon name="eye" size="sm" />
      </button>
    </span>
  );
}
