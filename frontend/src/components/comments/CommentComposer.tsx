import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { maxCommentLength } from './useTaskComments';

interface CommentComposerProps {
  posting: boolean;
  onPost: (draft: string) => Promise<boolean>;
}

export default function CommentComposer({ posting, onPost }: CommentComposerProps) {
  const { t } = useTranslation();
  const [draft, setDraft] = useState('');

  const post = async () => {
    if (await onPost(draft)) {
      setDraft('');
    }
  };

  return (
    <div className="comment-composer">
      <textarea
        value={draft}
        onChange={(event) => setDraft(event.target.value)}
        placeholder={t('taskComments.placeholder')}
        className="comment-textarea"
        rows={3}
        maxLength={maxCommentLength}
      />
      <div className="comment-composer-actions">
        {draft.length > maxCommentLength * 0.9 && (
          <span className="comment-length">{t('taskComments.remaining', { count: maxCommentLength - draft.length })}</span>
        )}
        <button type="button" className="comment-post-btn" onClick={post} disabled={posting || !draft.trim()}>
          {t('taskComments.post')}
        </button>
      </div>
    </div>
  );
}
