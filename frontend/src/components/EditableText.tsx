import { useState, useRef, useEffect, type KeyboardEvent } from 'react';
import Icon from './Icon';
import LengthHint from './LengthHint';
import { NAME_MAX_LENGTH } from '../services/textLimits';

interface EditableTextProps<Id> {
  id: Id;
  text: string;
  onUpdate: (id: Id, value: string, type: string) => Promise<boolean | void> | boolean | void;
  label: string;
  renameLabel?: string;
  className?: string;
  inputClassName?: string;
  type?: string;
  disabled?: boolean;
  maxLength?: number;
}

function EditableText<Id>({
  id,
  text,
  onUpdate,
  label,
  renameLabel,
  className = '',
  inputClassName = '',
  type = 'default',
  disabled = false,
  maxLength = NAME_MAX_LENGTH
}: EditableTextProps<Id>) {
  const [isEditing, setIsEditing] = useState(false);
  const [value, setValue] = useState(text);
  const inputRef = useRef<HTMLInputElement>(null);
  const renameRef = useRef<HTMLButtonElement>(null);
  const fromRenameButton = useRef(false);

  useEffect(() => {
    if (isEditing && inputRef.current) {
      inputRef.current.focus();
      inputRef.current.select();
    } else if (!isEditing && fromRenameButton.current) {
      fromRenameButton.current = false;
      if (!document.activeElement || document.activeElement === document.body) {
        renameRef.current?.focus();
      }
    }
  }, [isEditing]);

  useEffect(() => {
    setValue(text);
  }, [text]);

  const handleBlur = async () => {
    const trimmed = value.trim();
    if (trimmed !== text && trimmed !== '') {
      const success = await onUpdate(id, trimmed, type);
      if (!success) {
        setValue(text);
      }
    } else if (trimmed === '') {
      setValue(text);
    }
    setIsEditing(false);
  };

  const handleKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') {
      inputRef.current?.blur();
    } else if (e.key === 'Escape') {
      setValue(text);
      setIsEditing(false);
    }
  };

  if (isEditing) {
    return (
      <span className="editable-text-editing">
        <input
          ref={inputRef}
          type="text"
          value={value}
          maxLength={maxLength}
          onChange={(e) => setValue(e.target.value)}
          onBlur={handleBlur}
          onKeyDown={handleKeyDown}
          className={`editable-text-input ${inputClassName}`}
          data-type={type}
          aria-label={label}
        />
        <LengthHint value={value} max={maxLength} />
      </span>
    );
  }

  return (
    <>
      <span
        onDoubleClick={() => !disabled && setIsEditing(true)}
        className={`editable-text ${className}`}
        title={text}
        data-type={type}
        aria-disabled={disabled}
      >
        {text}
      </span>
      {renameLabel && !disabled && (
        <button
          ref={renameRef}
          type="button"
          className="icon-btn rename-btn"
          title={renameLabel}
          aria-label={renameLabel}
          onClick={() => {
            fromRenameButton.current = true;
            setIsEditing(true);
          }}
        >
          <Icon name="edit" size="sm" />
        </button>
      )}
    </>
  );
}

export default EditableText;
