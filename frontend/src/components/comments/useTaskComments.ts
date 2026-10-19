import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
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
import { logged } from '../../services/logged';
import { taskKeys } from '../../services/taskQueries';
import type { Id, TaskComment } from '../../types/api';

const MAX_PAGE_SIZE = 100;

export const maxCommentLength = MAX_COMMENT_LENGTH || 2000;
const pageSize = COMMENT_PAGE_SIZE || 25;

const usable = (body: string) => body.length > 0 && body.length <= maxCommentLength;

interface Thread {
  comments: TaskComment[];
  total: number;
}

const NONE: TaskComment[] = [];

export function useTaskComments(taskId: Id) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const key = taskKeys.comments(taskId);
  const [posting, setPosting] = useState(false);

  // A read after a write asks for as many comments as are on screen, so the thread does not shrink.
  const query = useQuery({
    queryKey: key,
    queryFn: async (): Promise<Thread> => {
      const shown = queryClient.getQueryData<Thread>(key)?.comments.length ?? 0;
      const size = Math.min(MAX_PAGE_SIZE, Math.max(pageSize, shown));
      const results = await logged('Error loading comments:', () => fetchTaskComments(taskId, { page: 0, size }));
      return { comments: results?.comments ?? [], total: results?.totalEntries ?? 0 };
    },
  });

  const reload = () => queryClient.invalidateQueries({ queryKey: key });

  const loadOlder = async () => {
    try {
      const shown = queryClient.getQueryData<Thread>(key)?.comments.length ?? 0;
      const results = await fetchTaskComments(taskId, { page: Math.floor(shown / pageSize), size: pageSize });
      queryClient.setQueryData<Thread>(key, previous => {
        const current = previous ?? { comments: NONE, total: 0 };
        const seen = new Set(current.comments.map(comment => comment.id));
        return {
          comments: [...current.comments, ...(results?.comments ?? []).filter(comment => !seen.has(comment.id))],
          total: results?.totalEntries ?? current.total,
        };
      });
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

  return {
    comments: query.data?.comments ?? NONE,
    total: query.data?.total ?? 0,
    loading: query.isPending,
    failed: query.isError,
    posting,
    loadOlder,
    post,
    edit,
    remove,
  };
}
