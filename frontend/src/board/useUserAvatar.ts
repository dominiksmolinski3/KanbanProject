import { useEffect, useState } from 'react';
import { getUserAvatar } from '../services/api';
import type { Id } from '../types/api';

// One request per account per session, however many cards it is on; the object URLs live as long as the page.
const cache = new Map<string, Promise<string | null>>();

export function clearAvatarCache() {
  for (const pending of cache.values()) {
    pending.then((url) => {
      if (url && url.startsWith('blob:')) URL.revokeObjectURL(url);
    });
  }
  cache.clear();
}

function load(userId: Id): Promise<string | null> {
  const key = String(userId);
  let pending = cache.get(key);
  if (!pending) {
    pending = getUserAvatar(userId).catch(() => null);
    cache.set(key, pending);
  }
  return pending;
}

export default function useUserAvatar(userId: Id | null | undefined): string | null {
  const [url, setUrl] = useState<string | null>(null);

  useEffect(() => {
    if (userId === undefined || userId === null) return undefined;
    let live = true;
    load(userId).then((loaded) => {
      if (live) setUrl(loaded || null);
    });
    return () => {
      live = false;
    };
  }, [userId]);

  return url;
}
