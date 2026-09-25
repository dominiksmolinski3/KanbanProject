import { useEffect, useState } from 'react';
import { getUserAvatar } from '../services/api';

// One request per account per session, however many cards it is on; the object URLs live as long as the page.
const cache = new Map();

export function clearAvatarCache() {
  for (const pending of cache.values()) {
    pending.then((url) => {
      if (url && url.startsWith('blob:')) URL.revokeObjectURL(url);
    });
  }
  cache.clear();
}

function load(userId) {
  const key = String(userId);
  if (!cache.has(key)) {
    cache.set(key, getUserAvatar(userId).catch(() => null));
  }
  return cache.get(key);
}

export default function useUserAvatar(userId) {
  const [url, setUrl] = useState(null);

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
