import { useTranslation } from 'react-i18next';
import { PIN_LENGTH } from '../../crypto/pin';

interface PinFieldProps {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
}

export function PinField({ id, label, value, onChange }: PinFieldProps) {
  return (
    <>
      <label className="field-label" htmlFor={id}>{label}</label>
      <input
        id={id}
        className="field-input messaging-pin-input"
        type="password"
        inputMode="numeric"
        autoComplete="off"
        dir="ltr"
        maxLength={PIN_LENGTH}
        value={value}
        onChange={(event) => onChange(event.target.value.replace(/\D/g, ''))}
      />
    </>
  );
}

interface PinFieldsProps {
  pin: string;
  repeat: string;
  onPin: (value: string) => void;
  onRepeat: (value: string) => void;
}

export default function PinFields({ pin, repeat, onPin, onRepeat }: PinFieldsProps) {
  const { t } = useTranslation();
  return (
    <>
      <PinField id="messaging-pin" label={t('devices.messaging.pin.label')} value={pin} onChange={onPin} />
      <PinField id="messaging-pin-repeat" label={t('devices.messaging.pin.repeat')} value={repeat} onChange={onRepeat} />
    </>
  );
}
