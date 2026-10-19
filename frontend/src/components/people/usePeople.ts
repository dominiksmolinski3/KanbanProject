import { useCallback, useEffect, useRef, useState } from 'react';
import { toast } from 'react-toastify';
import { useTranslation } from 'react-i18next';
import { deleteUser, fetchUsers, getUserAvatar, uploadUserAvatar } from '../../services/api';
import type { Id, User } from '../../types/api';

const AVATAR_MAX_BYTES = 1024 * 1024;
const AVATAR_TYPES = ['image/jpeg', 'image/png', 'image/webp', 'image/gif'];
const AVATAR_FETCH_ATTEMPTS = 3;
const AVATAR_RETRY_DELAY_MS = 1000;

export const AVATAR_ACCEPT = AVATAR_TYPES.join(',');

async function fetchFreshAvatar(userId: Id): Promise<string | null> {
  for (let attempt = 1; attempt <= AVATAR_FETCH_ATTEMPTS; attempt++) {
    const url = await getUserAvatar(userId);
    if (url) return url;
    if (attempt < AVATAR_FETCH_ATTEMPTS) {
      await new Promise(resolve => setTimeout(resolve, AVATAR_RETRY_DELAY_MS));
    }
  }
  return null;
}

export function usePeople() {
  const { t } = useTranslation();
  const [users, setUsers] = useState<User[]>([]);
  const [avatars, setAvatars] = useState<Record<Id, string>>({});
  const latestAvatars = useRef(avatars);
  latestAvatars.current = avatars;

  const load = useCallback(async () => {
    try {
      const data = await fetchUsers();
      setUsers(data);

      await Promise.all(data.map(async (user) => {
        const avatarUrl = await getUserAvatar(user.id);
        if (avatarUrl) {
          setAvatars(previous => ({ ...previous, [user.id]: avatarUrl }));
        }
      }));
    } catch (error) {
      console.error(t('usersManagement.messages.loadError'), error);
      toast.error(t('usersManagement.messages.loadError'));
    }
  }, [t]);

  useEffect(() => {
    load();

    return () => {
      Object.values(latestAvatars.current).forEach(url => {
        URL.revokeObjectURL(url);
      });
    };
  }, [load]);

  const uploadAvatar = async (userId: Id, file: File) => {
    try {
      if (file.size > AVATAR_MAX_BYTES) {
        toast.info(t('usersManagement.messages.fileTooLarge'));
        return;
      }

      if (!AVATAR_TYPES.includes(file.type)) {
        toast.info(t('usersManagement.messages.fileTypeError'));
        return;
      }

      await uploadUserAvatar(userId, file);

      const fresh = await fetchFreshAvatar(userId);
      if (fresh) {
        const previous = latestAvatars.current[userId];
        if (previous) {
          URL.revokeObjectURL(previous);
        }
        setAvatars(current => ({ ...current, [userId]: fresh }));
      }

      toast.info(t('usersManagement.messages.avatarUpdated'));
    } catch (error) {
      console.error('Error uploading avatar:', error);
      toast.warning(`${t('usersManagement.messages.avatarError')} ${(error as Error).message}`);
    }
  };

  const removeUser = async (userId: Id) => {
    if (!window.confirm(t('usersManagement.messages.deleteConfirm'))) {
      return;
    }
    try {
      await deleteUser(userId);
      setUsers(current => current.filter(user => user.id !== userId));
    } catch (error) {
      console.error('Error:', error);
      toast.error(t('usersManagement.messages.deleteError'));
    }
  };

  return { users, avatars, uploadAvatar, removeUser };
}
