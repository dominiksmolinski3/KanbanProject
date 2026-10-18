import { useEffect, useState } from 'react';
import { getAllLabels, fetchUsers } from '../../services/api';
import type { User } from '../../types/api';

export function useFacetOptions(isOpen: boolean) {
  const [labels, setLabels] = useState<string[]>([]);
  const [users, setUsers] = useState<User[]>([]);

  useEffect(() => {
    if (!isOpen) {
      return;
    }
    let cancelled = false;
    Promise.all([getAllLabels(), fetchUsers()])
      .then(([boardLabels, boardUsers]) => {
        if (!cancelled) {
          setLabels(Array.isArray(boardLabels) ? boardLabels : []);
          setUsers(Array.isArray(boardUsers) ? boardUsers : []);
        }
      })
      .catch(() => {
      });
    return () => {
      cancelled = true;
    };
  }, [isOpen]);

  return { labels, users };
}
