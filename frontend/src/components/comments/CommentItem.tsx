import type { CSSProperties } from 'react';
import { useTranslation } from 'react-i18next';
import { hueOf, initialsOf } from '../../board/cardModel';
import type { TaskComment } from '../../types/api';
import CommentActions from './CommentActions';
import CommentEditForm from './CommentEditForm';

interface CommentItemProps {
  comment: TaskComment;
  mine: boolean;
  canDelete: boolean;
  readOnly: boolean;
  editing: boolean;
  confirmingDelete: boolean;
  onEdit: () => void;
  onSaveEdit: (draft: string) => void;
  onCancelEdit: () => void;
  onAskDelete: () => void;
  onCancelDelete: () => void;
  onDelete: () => void;
}

export default function CommentItem({
  comment, mine, canDelete, readOnly, editing, confirmingDelete,
  onEdit, onSaveEdit, onCancelEdit, onAskDelete, onCancelDelete, onDelete
}: CommentItemProps) {
  const { t, i18n } = useTranslation();

  return (
    <li className="comment-item">
      <span
        className="comment-avatar"
        aria-hidden="true"
        style={{ '--avatar-hue': hueOf(comment.authorId ?? comment.authorName ?? '') } as CSSProperties}
      >
        {initialsOf(comment.authorName || '')}
      </span>
      <div className="comment-main">
        <div className="comment-meta">
          <span className="comment-author">{comment.authorName || t('taskComments.formerMember')}</span>
          <time dateTime={comment.createdAt}>{new Date(comment.createdAt).toLocaleString(i18n?.language || undefined)}</time>
          {comment.editedAt && <span className="comment-edited">{t('taskComments.edited')}</span>}
        </div>

        {editing ? (
          <CommentEditForm initial={comment.body} onSave={onSaveEdit} onCancel={onCancelEdit} />
        ) : (
          <p className="comment-body">{comment.body}</p>
        )}

        {!editing && !readOnly && (
          <CommentActions
            mine={mine}
            canDelete={canDelete}
            confirmingDelete={confirmingDelete}
            onEdit={onEdit}
            onAskDelete={onAskDelete}
            onCancelDelete={onCancelDelete}
            onDelete={onDelete}
          />
        )}
      </div>
    </li>
  );
}
