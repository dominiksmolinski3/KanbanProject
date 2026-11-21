import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import { useTranslation } from 'react-i18next';
import type { ArchivedMessage } from '../../crypto/engine';
import { MAX_FILES, MAX_TEXT_LENGTH } from '../../crypto/payload';
import { MAX_FILE_BYTES } from '../../crypto/attachments';
import { useAsyncAction } from '../../hooks/useAsyncAction';
import Icon from '../Icon';
import { findMentions, mentionQuery, type Person } from './mentions';

export type Draft = { mode: 'reply' | 'edit'; message: ArchivedMessage } | null;

export interface Outgoing {
  mentions: number[];
  files: File[];
}

interface ComposerProps {
  disabled: boolean;
  draft: Draft;
  draftAuthor: string;
  people?: Person[];
  attachments?: boolean;
  onCancelDraft: () => void;
  onSend: (text: string, outgoing: Outgoing) => Promise<void>;
  onTyping: () => void;
}

const fold = (value: string) => value.normalize('NFD').replace(/\p{Diacritic}/gu, '').toLowerCase();

export default function Composer({ disabled, draft, draftAuthor, people = [], attachments = false, onCancelDraft, onSend, onTyping }: ComposerProps) {
  const { t } = useTranslation();
  const [text, setText] = useState('');
  const [files, setFiles] = useState<File[]>([]);
  const [caret, setCaret] = useState(0);
  const [active, setActive] = useState(0);
  const [fileError, setFileError] = useState<string | null>(null);
  const { pending, error, run } = useAsyncAction();
  const field = useRef<HTMLTextAreaElement>(null);
  const picker = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (draft?.mode === 'edit') setText(draft.message.text);
    if (draft) field.current?.focus();
  }, [draft]);

  const typed = mentionQuery(text, caret);
  const suggestions = typed
    ? people.filter((person) => fold(person.name).includes(fold(typed.query))).slice(0, 6)
    : [];
  const choosing = suggestions.length > 0 && draft?.mode !== 'edit';

  const choose = (person: Person) => {
    if (!typed) return;
    const next = `${text.slice(0, typed.start)}@${person.name} ${text.slice(caret)}`;
    const position = typed.start + person.name.length + 2;
    setText(next);
    setCaret(position);
    setActive(0);
    requestAnimationFrame(() => {
      field.current?.focus();
      field.current?.setSelectionRange(position, position);
    });
  };

  const submit = async (event?: FormEvent) => {
    event?.preventDefault();
    const trimmed = text.trim();
    if ((!trimmed && !files.length) || pending || disabled) return;
    const sent = await run(
      () => onSend(trimmed, { mentions: findMentions(trimmed, people), files }),
      t('messages.errors.send'),
    );
    if (sent) {
      setText('');
      setFiles([]);
    }
  };

  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (choosing && (event.key === 'ArrowDown' || event.key === 'ArrowUp')) {
      event.preventDefault();
      setActive((index) => (index + (event.key === 'ArrowDown' ? 1 : suggestions.length - 1)) % suggestions.length);
      return;
    }
    if (choosing && (event.key === 'Enter' || event.key === 'Tab') && !event.nativeEvent.isComposing) {
      event.preventDefault();
      choose(suggestions[Math.min(active, suggestions.length - 1)]);
      return;
    }
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault();
      void submit();
    }
    if (event.key === 'Escape' && draft) {
      onCancelDraft();
      setText('');
    }
  };

  const pick = (chosen: FileList | null) => {
    if (!chosen?.length) return;
    const picked = [...chosen];
    if (files.length + picked.length > MAX_FILES) {
      setFileError(t('messages.files.tooMany', { count: MAX_FILES }));
    } else if (picked.some((file) => file.size > MAX_FILE_BYTES)) {
      setFileError(t('messages.files.tooLarge', { size: Math.round(MAX_FILE_BYTES / (1024 * 1024)) }));
    } else {
      setFileError(null);
      setFiles([...files, ...picked]);
    }
    if (picker.current) picker.current.value = '';
  };

  return (
    <form className="composer" onSubmit={submit}>
      {draft && (
        <div className="composer-draft">
          <span>
            <strong>{t(draft.mode === 'edit' ? 'messages.composer.editing' : 'messages.composer.replying', { name: draftAuthor })}</strong>
            <span className="composer-draft-text">{draft.message.text}</span>
          </span>
          <button type="button" className="btn-ghost" aria-label={t('messages.composer.cancelDraft')}
            onClick={() => { onCancelDraft(); setText(''); }}>
            <Icon name="close" size="sm" />
          </button>
        </div>
      )}
      {files.length > 0 && (
        <ul className="composer-files" aria-label={t('messages.files.chosen')}>
          {files.map((file, index) => (
            <li key={`${file.name}-${index}`}>
              <Icon name="file" size="sm" />
              <span>{file.name}</span>
              <button type="button" className="btn-ghost" aria-label={t('messages.files.remove', { name: file.name })}
                onClick={() => setFiles(files.filter((_, at) => at !== index))}>
                <Icon name="close" size="sm" />
              </button>
            </li>
          ))}
        </ul>
      )}
      {choosing && (
        <ul className="composer-mentions" role="listbox" aria-label={t('messages.composer.mentions')}>
          {suggestions.map((person, index) => (
            <li key={person.userId} role="option" aria-selected={index === active}>
              <button type="button" tabIndex={-1} className={index === active ? 'is-active' : ''}
                onMouseDown={(event) => { event.preventDefault(); choose(person); }}>
                {person.name}
              </button>
            </li>
          ))}
        </ul>
      )}
      <div className="composer-row">
        {attachments && draft?.mode !== 'edit' && (
          <>
            <input ref={picker} type="file" multiple hidden tabIndex={-1} aria-hidden="true"
              data-testid="composer-file-input" onChange={(event) => pick(event.target.files)} />
            <button type="button" className="btn-ghost composer-attach" disabled={disabled || pending}
              aria-label={t('messages.files.attach')} onClick={() => picker.current?.click()}>
              <Icon name="paperclip" size="sm" />
            </button>
          </>
        )}
        <textarea
          ref={field}
          rows={1}
          value={text}
          maxLength={MAX_TEXT_LENGTH}
          disabled={disabled}
          aria-label={t('messages.composer.label')}
          aria-autocomplete={people.length ? 'list' : undefined}
          placeholder={t('messages.composer.placeholder')}
          onChange={(event) => {
            setText(event.target.value);
            setCaret(event.target.selectionStart ?? event.target.value.length);
            setActive(0);
            if (event.target.value) onTyping();
          }}
          onSelect={(event) => setCaret(event.currentTarget.selectionStart ?? 0)}
          onKeyDown={onKeyDown}
        />
        <button type="submit" className="btn btn-primary composer-send" disabled={disabled || pending || (!text.trim() && !files.length)}
          aria-label={t('messages.composer.send')}>
          <Icon name="send" size="sm" />
        </button>
      </div>
      {(error || fileError) && <p className="messages-error" role="alert">{error ?? fileError}</p>}
    </form>
  );
}
