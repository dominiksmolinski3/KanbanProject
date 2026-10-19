import { useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { toast } from 'react-toastify';
import { useTranslation } from 'react-i18next';
import { useKanban } from '../../context/KanbanContext';
import { messageOf } from '../../context/kanban/reportError';
import { USERS_KEY, useUsers } from '../../hooks/useUsers';
import { getUserAvatar, updateUserWipLimit } from '../../services/api';
import type { Id, User } from '../../types/api';

export type BenchUser = User & { role?: string; taskCount?: number };

const NONE: BenchUser[] = [];

export function useBenchUsers() {
  const { refreshTasks } = useKanban();
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const query = useUsers();
  const users: BenchUser[] = query.data ?? NONE;
  const [avatars, setAvatars] = useState<Record<Id, string>>({});
  const latestAvatars = useRef(avatars);
  latestAvatars.current = avatars;

  const failure = query.error;
  useEffect(() => {
    if (failure) {
      console.error('Error loading users:', failure);
      toast.error(t('notifications.usersLoadError', { message: messageOf(failure) }));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [failure]);

  const people = users.map(user => user.id).join(',');
  useEffect(() => {
    users.forEach(async (user) => {
      try {
        const avatarUrl = await getUserAvatar(user.id);
        if (avatarUrl) {
          setAvatars(previous => ({ ...previous, [user.id]: avatarUrl }));
        }
      } catch (avatarError) {
        console.error(`Error loading avatar for user ${user.id}:`, avatarError);
      }
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [people]);

  useEffect(() => () => {
    Object.values(latestAvatars.current).forEach(url => {
      if (url && url.startsWith('blob:')) {
        URL.revokeObjectURL(url);
      }
    });
  }, []);

  const saveWipLimit = async (userId: Id, newLimit: number | null): Promise<boolean> => {
    try {
      await updateUserWipLimit(userId, newLimit);

      queryClient.setQueryData<User[]>(USERS_KEY, previous =>
        (previous ?? NONE).map(user => (user.id === userId ? { ...user, wipLimit: newLimit as number } : user)));

      const userName = users.find(user => user.id === userId)?.name || t('bench.thisUser');
      const limitText = newLimit ? newLimit : t('bench.unlimited');
      toast.success(t('notifications.wipLimitUpdated', { name: userName, limit: limitText }));
      refreshTasks();
      return true;
    } catch (saveError) {
      console.error('Error updating WIP limit:', saveError);
      toast.error(t('notifications.wipLimitUpdateError', { message: messageOf(saveError) }));
      return false;
    }
  };

  return {
    users,
    loading: query.isPending,
    error: query.isError ? t('bench.loadError') : null,
    avatars,
    saveWipLimit,
  };
}
