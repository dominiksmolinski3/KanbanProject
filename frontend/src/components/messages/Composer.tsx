import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import { useTranslation } from 'react-i18next';
import type { ArchivedMessage } from '../../crypto/engine';
import { MAX_TEXT_LENGTH } from '../../crypto/payload';
import { useAsyncAction } from '../../hooks/useAsyncAction';
import Icon from '../Icon';

export type Draft = { mode: 'reply' | 'edit'; message: ArchivedMessage } | null;

interface ComposerProps {
  disabled: boolean;
  draft: Draft;
  draftAuthor: string;
  onCancelDraft: () => void;
  onSend: (text: string) => Promise<void>;
  onTyping: () => void;
}

export default function Composer({ disabled, draft, draftAuthor, onCancelDraft, onSend, onTyping }: ComposerProps) {
  const { t } = useTranslation();
  const [text, setText] = useState('');
  const { pending, error, run } = useAsyncAction();
  const field = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    if (draft?.mode === 'edit') setText(draft.message.text);
    if (draft) field.current?.focus();
  }, [draft]);

  const submit = async (event?: FormEvent) => {
    event?.preventDefault();
    const trimmed = text.trim();
    if (!trimmed || pending || disabled) return;
    const sent = await run(() => onSend(trimmed), t('messages.errors.send'));
    if (sent) setText('');
  };

  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault();
      void submit();
    }
    if (event.key === 'Escape' && draft) {
      onCancelDraft();
      setText('');
    }
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
      <div className="composer-row">
        <textarea
          ref={field}
          rows={1}
          value={text}
          maxLength={MAX_TEXT_LENGTH}
          disabled={disabled}
          aria-label={t('messages.composer.label')}
          placeholder={t('messages.composer.placeholder')}
          onChange={(event) => {
            setText(event.target.value);
            if (event.target.value) onTyping();
          }}
          onKeyDown={onKeyDown}
        />
        <button type="submit" className="btn btn-primary composer-send" disabled={disabled || pending || !text.trim()}
          aria-label={t('messages.composer.send')}>
          <Icon name="send" size="sm" />
        </button>
      </div>
      {error && <p className="messages-error" role="alert">{error}</p>}
    </form>
  );
}
