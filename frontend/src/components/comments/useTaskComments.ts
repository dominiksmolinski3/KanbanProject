import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { toast } from 'react-toastify';
import {
  fetchTaskComments,
  addTaskComment,
  editTaskComment,
  deleteTaskComment,
  MAX_COMMENT_LENGTH,
  COMMENT_PAGE_SIZE
} from '../../services/api';
import type { Id, TaskComment } from '../../types/api';

const MAX_PAGE_SIZE = 100;

export const maxCommentLength = MAX_COMMENT_LENGTH || 2000;
const pageSize = COMMENT_PAGE_SIZE || 25;

const usable = (body: string) => body.length > 0 && body.length <= maxCommentLength;

export function useTaskComments(taskId: Id) {
  const { t } = useTranslation();
  const [comments, setComments] = useState<TaskComment[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);
  const [posting, setPosting] = useState(false);

  const loadedCount = useRef(0);
  loadedCount.current = comments.length;

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
  }, [taskId]);

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

  const post = async (draft: string): Promise<boolean> => {
    const body = draft.trim();
    if (!usable(body)) {
      return false;
    }
    setPosting(true);
    try {
      await addTaskComment(taskId, body);
      await reload();
      return true;
    } catch (error) {
      console.error('Error adding comment:', error);
      toast.error(t('taskComments.postError'));
      return false;
    } finally {
      setPosting(false);
    }
  };

  const edit = async (commentId: number, draft: string): Promise<boolean> => {
    const body = draft.trim();
    if (!usable(body)) {
      return false;
    }
    try {
      await editTaskComment(taskId, commentId, body);
      await reload();
      return true;
    } catch (error) {
      console.error('Error editing comment:', error);
      toast.error(t('taskComments.editError'));
      return false;
    }
  };

  const remove = async (commentId: number): Promise<boolean> => {
    try {
      await deleteTaskComment(taskId, commentId);
      await reload();
      return true;
    } catch (error) {
      console.error('Error deleting comment:', error);
      toast.error(t('taskComments.deleteError'));
      return false;
    }
  };

  return { comments, total, loading, failed, posting, loadOlder, post, edit, remove };
}
