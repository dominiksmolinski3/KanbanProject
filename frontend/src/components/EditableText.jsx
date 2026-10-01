import React, { useState, useRef, useEffect } from 'react';
import LengthHint from './LengthHint';
import { NAME_MAX_LENGTH } from '../services/textLimits';

function EditableText({
  id,
  text,
  onUpdate,
  className = "",
  inputClassName = "",
  type = "default",
  disabled = false,
  maxLength = NAME_MAX_LENGTH
}) {
  const [isEditing, setIsEditing] = useState(false);
  const [value, setValue] = useState(text);
  const inputRef = useRef(null);
  const spanRef = useRef(null);

  useEffect(() => {
    if (isEditing && inputRef.current) {
      inputRef.current.focus();
      inputRef.current.select();
    }
  }, [isEditing]);

  useEffect(() => {
    setValue(text);
  }, [text]);

  const handleDoubleClick = () => {
    if (disabled) return;
    setIsEditing(true);
  };

  const handleBlur = async () => {
    if (value.trim() !== text && value.trim() !== '') {
      const success = await onUpdate(id, value.trim(), type);
      if (!success) {
        setValue(text); 
      }
    } else if (value.trim() === '') {
      setValue(text); 
    }
    setIsEditing(false);
  };

  const handleKeyDown = (e) => {
    if (e.key === 'Enter') {
      inputRef.current.blur();
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
        />
        <LengthHint value={value} max={maxLength} />
      </span>
    );
  }

  return (
    <span
      ref={spanRef}
      onDoubleClick={handleDoubleClick}
      className={`editable-text ${className}`}
      title={text}
      data-type={type}
      aria-disabled={disabled}
    >
      {text}
    </span>
  );
}

export default EditableText;