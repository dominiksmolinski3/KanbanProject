import { useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { toast } from 'react-toastify';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../context/AuthContext';
import { USERS_KEY, useUsers } from '../../hooks/useUsers';
import { deleteUser, getUserAvatar, uploadUserAvatar } from '../../services/api';
import type { Id, User } from '../../types/api';

const AVATAR_MAX_BYTES = 1024 * 1024;
const AVATAR_TYPES = ['image/jpeg', 'image/png', 'image/webp', 'image/gif'];
const AVATAR_FETCH_ATTEMPTS = 3;
const AVATAR_RETRY_DELAY_MS = 1000;

export const AVATAR_ACCEPT = AVATAR_TYPES.join(',');

const NONE: User[] = [];

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
  const { logout } = useAuth();
  const queryClient = useQueryClient();
  const query = useUsers();
  const users = query.data ?? NONE;
  const [avatars, setAvatars] = useState<Record<Id, string>>({});
  const latestAvatars = useRef(avatars);
  latestAvatars.current = avatars;

  const failure = query.error;
  useEffect(() => {
    if (failure) {
      console.error(t('usersManagement.messages.loadError'), failure);
      toast.error(t('usersManagement.messages.loadError'));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [failure]);

  const people = users.map(user => user.id).join(',');
  useEffect(() => {
    users.forEach(async (user) => {
      const avatarUrl = await getUserAvatar(user.id);
      if (avatarUrl) {
        setAvatars(previous => ({ ...previous, [user.id]: avatarUrl }));
      }
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [people]);

  useEffect(() => () => {
    Object.values(latestAvatars.current).forEach(url => {
      URL.revokeObjectURL(url);
    });
  }, []);

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
      queryClient.setQueryData<User[]>(USERS_KEY, current => (current ?? NONE).filter(user => user.id !== userId));
      await logout();
    } catch (error) {
      console.error('Error:', error);
      toast.error(t('usersManagement.messages.deleteError'));
    }
  };

  return { users, avatars, uploadAvatar, removeUser };
}
