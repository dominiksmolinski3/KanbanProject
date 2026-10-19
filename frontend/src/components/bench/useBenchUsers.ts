import { useEffect, useRef, useState } from 'react';
import { toast } from 'react-toastify';
import { useTranslation } from 'react-i18next';
import { useKanban } from '../../context/KanbanContext';
import { messageOf } from '../../context/kanban/reportError';
import { fetchUsers, getUserAvatar, updateUserWipLimit } from '../../services/api';
import type { Id, User } from '../../types/api';

export type BenchUser = User & { role?: string; taskCount?: number };

export function useBenchUsers() {
  const { refreshTasks } = useKanban();
  const { t } = useTranslation();
  const [users, setUsers] = useState<BenchUser[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [avatars, setAvatars] = useState<Record<Id, string>>({});
  const latestAvatars = useRef(avatars);
  latestAvatars.current = avatars;

  useEffect(() => {
    const loadUsers = async () => {
      try {
        const allUsers: BenchUser[] = await fetchUsers();
        setUsers(allUsers);

        await Promise.all(allUsers.map(async (user) => {
          try {
            const avatarUrl = await getUserAvatar(user.id);
            if (avatarUrl) {
              setAvatars(previous => ({ ...previous, [user.id]: avatarUrl }));
            }
          } catch (avatarError) {
            console.error(`Error loading avatar for user ${user.id}:`, avatarError);
          }
        }));
        setLoading(false);
      } catch (loadError) {
        console.error('Error loading users:', loadError);
        setError(t('bench.loadError'));
        toast.error(t('notifications.usersLoadError', { message: messageOf(loadError) }));
        setLoading(false);
      }
    };

    loadUsers();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

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

      setUsers(previous => previous.map(user => (user.id === userId ? { ...user, wipLimit: newLimit as number } : user)));

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

  return { users, loading, error, avatars, saveWipLimit };
}
