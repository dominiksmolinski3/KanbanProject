import { useRef, useState } from 'react';

export function useEditableField<E extends HTMLInputElement | HTMLTextAreaElement>() {
  const [value, setValue] = useState('');
  const [original, setOriginal] = useState('');
  const [editing, setEditing] = useState(false);
  const inputRef = useRef<E>(null);

  const load = (loaded: string) => {
    setValue(loaded);
    setOriginal(loaded);
  };

  const start = () => {
    setOriginal(value);
    setEditing(true);
    setTimeout(() => inputRef.current?.focus(), 0);
  };

  const cancel = () => {
    setValue(original);
    setEditing(false);
  };

  const commit = () => {
    setOriginal(value);
    setEditing(false);
  };

  return { value, setValue, editing, inputRef, load, start, cancel, commit };
}
