import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { maxCommentLength } from './useTaskComments';

interface CommentEditFormProps {
  initial: string;
  onSave: (draft: string) => void;
  onCancel: () => void;
}

export default function CommentEditForm({ initial, onSave, onCancel }: CommentEditFormProps) {
  const { t } = useTranslation();
  const [draft, setDraft] = useState(initial);

  return (
    <div className="comment-edit-form">
      <textarea
        value={draft}
        onChange={(event) => setDraft(event.target.value)}
        className="comment-textarea"
        rows={3}
        maxLength={maxCommentLength}
        aria-label={t('taskComments.edit')}
      />
      <div className="comment-composer-actions">
        <button type="button" className="comment-post-btn" onClick={() => onSave(draft)} disabled={!draft.trim()}>
          {t('taskComments.save')}
        </button>
        <button type="button" className="comment-cancel-btn" onClick={onCancel}>
          {t('taskComments.cancel')}
        </button>
      </div>
    </div>
  );
}
