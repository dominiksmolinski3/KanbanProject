import { useQuery } from '@tanstack/react-query';
import { fetchUsers } from '../services/api';
import type { User } from '../types/api';

export const USERS_KEY = ['users'] as const;

const NONE: User[] = [];

export function useUsers(enabled = true) {
  return useQuery({
    queryKey: USERS_KEY,
    enabled,
    queryFn: async () => {
      const users = await fetchUsers();
      return Array.isArray(users) ? users : NONE;
    },
  });
}
