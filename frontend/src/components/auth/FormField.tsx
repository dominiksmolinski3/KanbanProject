import type { InputHTMLAttributes } from 'react';
import { useFormContext } from 'react-hook-form';
import type { AuthFields } from './authFields';

interface FormFieldProps extends InputHTMLAttributes<HTMLInputElement> {
  name: keyof AuthFields;
  label?: string;
}

export default function FormField({ name, label, id, ...inputProps }: FormFieldProps) {
  const { register } = useFormContext<AuthFields>();

  return (
    <div className="form-group">
      {label && <label className="field-label" htmlFor={id}>{label}</label>}
      <input id={id} required {...inputProps} {...register(name)} />
    </div>
  );
}
