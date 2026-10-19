import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useKanban } from '../context/KanbanContext';
import { useAuth } from '../context/AuthContext';
import type { Id, TaskComment } from '../types/api';
import CommentComposer from './comments/CommentComposer';
import CommentItem from './comments/CommentItem';
import { useTaskComments } from './comments/useTaskComments';
import '../styles/components/TaskComments.css';

function TaskComments({ taskId }: { taskId: Id }) {
  const { t } = useTranslation();
  const { readOnly, activeBoard } = useKanban();
  const user = useAuth()?.user;
  const { comments, total, loading, failed, posting, loadOlder, post, edit, remove } = useTaskComments(taskId);

  const [editingId, setEditingId] = useState<number | null>(null);
  const [confirmingDeleteId, setConfirmingDeleteId] = useState<number | null>(null);

  const isMine = (comment: TaskComment) => user?.id != null && comment.authorId === user.id;
  const canDelete = (comment: TaskComment) => !readOnly && (isMine(comment) || activeBoard?.owned === true);

  const saveEdit = async (commentId: number, draft: string) => {
    if (await edit(commentId, draft)) {
      setEditingId(null);
    }
  };

  const deleteComment = async (commentId: number) => {
    if (await remove(commentId)) {
      setConfirmingDeleteId(null);
    }
  };

  return (
    <div className="task-comments-section">
      <h4>{t('taskComments.heading')}</h4>

      {!readOnly && <CommentComposer posting={posting} onPost={post} />}

      {loading && <p className="comments-empty">{t('taskComments.loading')}</p>}
      {!loading && failed && <p className="comments-empty">{t('taskComments.loadError')}</p>}
      {!loading && !failed && comments.length === 0 && (
        <p className="comments-empty">{t('taskComments.empty')}</p>
      )}

      {comments.length > 0 && (
        <ul className="comments-list">
          {comments.map(comment => (
            <CommentItem
              key={comment.id}
              comment={comment}
              mine={isMine(comment)}
              canDelete={canDelete(comment)}
              readOnly={readOnly}
              editing={editingId === comment.id}
              confirmingDelete={confirmingDeleteId === comment.id}
              onEdit={() => setEditingId(comment.id)}
              onSaveEdit={(draft) => saveEdit(comment.id, draft)}
              onCancelEdit={() => setEditingId(null)}
              onAskDelete={() => setConfirmingDeleteId(comment.id)}
              onCancelDelete={() => setConfirmingDeleteId(null)}
              onDelete={() => deleteComment(comment.id)}
            />
          ))}
        </ul>
      )}

      {comments.length < total && (
        <button type="button" className="comments-load-older" onClick={loadOlder}>
          {t('taskComments.loadOlder')}
        </button>
      )}
    </div>
  );
}

export default TaskComments;
