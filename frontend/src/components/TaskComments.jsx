import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { toast } from 'react-toastify';
import { useKanban } from '../context/KanbanContext';
import { useAuth } from '../context/AuthContext';
import {
  fetchTaskComments,
  addTaskComment,
  editTaskComment,
  deleteTaskComment,
  MAX_COMMENT_LENGTH,
  COMMENT_PAGE_SIZE
} from '../services/api';
import '../styles/components/TaskComments.css';

const MAX_PAGE_SIZE = 100;

function TaskComments({ taskId }) {
  const { t } = useTranslation();
  const { readOnly, activeBoard } = useKanban();
  const { user } = useAuth() || {};

  const [comments, setComments] = useState([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);
  const [draft, setDraft] = useState('');
  const [posting, setPosting] = useState(false);
  const [editingId, setEditingId] = useState(null);
  const [editDraft, setEditDraft] = useState('');
  const [confirmingDeleteId, setConfirmingDeleteId] = useState(null);

  const loadedCount = useRef(0);
  loadedCount.current = comments.length;

  const maxLength = MAX_COMMENT_LENGTH || 2000;
  const pageSize = COMMENT_PAGE_SIZE || 25;

  const reload = useCallback(async () => {
    try {
      const size = Math.min(MAX_PAGE_SIZE, Math.max(pageSize, loadedCount.current));
      const results = await fetchTaskComments(taskId, { page: 0, size });
      setComments(results?.comments ?? []);
      setTotal(results?.totalEntries ?? 0);
      setFailed(false);
    } catch (error) {
      console.error('Error loading comments:', error);
      setFailed(true);
    } finally {
      setLoading(false);
    }
  }, [taskId, pageSize]);

  useEffect(() => {
    reload();
  }, [reload]);

  useEffect(() => {
    const onChanged = () => reload();
    window.addEventListener('task-comments-changed', onChanged);
    return () => window.removeEventListener('task-comments-changed', onChanged);
  }, [reload]);

  const loadOlder = async () => {
    try {
      const page = Math.floor(comments.length / pageSize);
      const results = await fetchTaskComments(taskId, { page, size: pageSize });
      const seen = new Set(comments.map(comment => comment.id));
      setComments([...comments, ...(results?.comments ?? []).filter(comment => !seen.has(comment.id))]);
      setTotal(results?.totalEntries ?? total);
    } catch (error) {
      console.error('Error loading older comments:', error);
      toast.error(t('taskComments.loadError'));
    }
  };

  const post = async () => {
    const body = draft.trim();
    if (!body || body.length > maxLength) {
      return;
    }
    setPosting(true);
    try {
      await addTaskComment(taskId, body);
      setDraft('');
      await reload();
    } catch (error) {
      console.error('Error adding comment:', error);
      toast.error(t('taskComments.postError'));
    } finally {
      setPosting(false);
    }
  };

  const startEditing = (comment) => {
    setEditingId(comment.id);
    setEditDraft(comment.body);
  };

  const saveEdit = async () => {
    const body = editDraft.trim();
    if (!body || body.length > maxLength) {
      return;
    }
    try {
      await editTaskComment(taskId, editingId, body);
      setEditingId(null);
      await reload();
    } catch (error) {
      console.error('Error editing comment:', error);
      toast.error(t('taskComments.editError'));
    }
  };

  const remove = async (commentId) => {
    try {
      await deleteTaskComment(taskId, commentId);
      setConfirmingDeleteId(null);
      await reload();
    } catch (error) {
      console.error('Error deleting comment:', error);
      toast.error(t('taskComments.deleteError'));
    }
  };

  const isMine = (comment) => user?.id != null && comment.authorId === user.id;
  const canDelete = (comment) => !readOnly && (isMine(comment) || activeBoard?.owned === true);

  return (
    <div className="task-comments-section">
      <h4>{t('taskComments.heading')}</h4>

      {!readOnly && (
        <div className="comment-composer">
          <textarea
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            placeholder={t('taskComments.placeholder')}
            className="comment-textarea"
            rows={3}
            maxLength={maxLength}
          />
          <div className="comment-composer-actions">
            {draft.length > maxLength * 0.9 && (
              <span className="comment-length">{t('taskComments.remaining', { count: maxLength - draft.length })}</span>
            )}
            <button
              type="button"
              className="comment-post-btn"
              onClick={post}
              disabled={posting || !draft.trim()}
            >
              {t('taskComments.post')}
            </button>
          </div>
        </div>
      )}

      {loading && <p className="comments-empty">{t('taskComments.loading')}</p>}
      {!loading && failed && <p className="comments-empty">{t('taskComments.loadError')}</p>}
      {!loading && !failed && comments.length === 0 && (
        <p className="comments-empty">{t('taskComments.empty')}</p>
      )}

      {comments.length > 0 && (
        <ul className="comments-list">
          {comments.map(comment => (
            <li key={comment.id} className="comment-item">
              <div className="comment-meta">
                <span className="comment-author">{comment.authorName || t('taskComments.formerMember')}</span>
                <time dateTime={comment.createdAt}>{new Date(comment.createdAt).toLocaleString()}</time>
                {comment.editedAt && <span className="comment-edited">{t('taskComments.edited')}</span>}
              </div>

              {editingId === comment.id ? (
                <div className="comment-edit-form">
                  <textarea
                    value={editDraft}
                    onChange={(event) => setEditDraft(event.target.value)}
                    className="comment-textarea"
                    rows={3}
                    maxLength={maxLength}
                    aria-label={t('taskComments.edit')}
                  />
                  <div className="comment-composer-actions">
                    <button type="button" className="comment-post-btn" onClick={saveEdit} disabled={!editDraft.trim()}>
                      {t('taskComments.save')}
                    </button>
                    <button type="button" className="comment-cancel-btn" onClick={() => setEditingId(null)}>
                      {t('taskComments.cancel')}
                    </button>
                  </div>
                </div>
              ) : (
                <p className="comment-body">{comment.body}</p>
              )}

              {editingId !== comment.id && !readOnly && (
                <div className="comment-actions">
                  {isMine(comment) && (
                    <button type="button" className="comment-action-btn" onClick={() => startEditing(comment)}>
                      {t('taskComments.edit')}
                    </button>
                  )}
                  {canDelete(comment) && (confirmingDeleteId === comment.id ? (
                    <>
                      <span className="comment-confirm">{t('taskComments.confirmDelete')}</span>
                      <button type="button" className="comment-action-btn danger" onClick={() => remove(comment.id)}>
                        {t('taskComments.delete')}
                      </button>
                      <button type="button" className="comment-action-btn" onClick={() => setConfirmingDeleteId(null)}>
                        {t('taskComments.cancel')}
                      </button>
                    </>
                  ) : (
                    <button type="button" className="comment-action-btn" onClick={() => setConfirmingDeleteId(comment.id)}>
                      {t('taskComments.delete')}
                    </button>
                  ))}
                </div>
              )}
            </li>
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
