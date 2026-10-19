import { useTranslation } from 'react-i18next';

interface CommentActionsProps {
  mine: boolean;
  canDelete: boolean;
  confirmingDelete: boolean;
  onEdit: () => void;
  onAskDelete: () => void;
  onCancelDelete: () => void;
  onDelete: () => void;
}

export default function CommentActions({
  mine, canDelete, confirmingDelete, onEdit, onAskDelete, onCancelDelete, onDelete
}: CommentActionsProps) {
  const { t } = useTranslation();

  return (
    <div className="comment-actions">
      {mine && (
        <button type="button" className="comment-action-btn" onClick={onEdit}>
          {t('taskComments.edit')}
        </button>
      )}
      {canDelete && (confirmingDelete ? (
        <>
          <span className="comment-confirm">{t('taskComments.confirmDelete')}</span>
          <button type="button" className="comment-action-btn danger" onClick={onDelete}>
            {t('taskComments.delete')}
          </button>
          <button type="button" className="comment-action-btn" onClick={onCancelDelete}>
            {t('taskComments.cancel')}
          </button>
        </>
      ) : (
        <button type="button" className="comment-action-btn" onClick={onAskDelete}>
          {t('taskComments.delete')}
        </button>
      ))}
    </div>
  );
}
