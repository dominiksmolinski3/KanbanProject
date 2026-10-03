import { useEffect, useState } from 'react';

const QUERY = '(max-width: 640px)';

const matches = () => typeof window.matchMedia === 'function' && window.matchMedia(QUERY).matches;

export default function useNarrowScreen() {
  const [narrow, setNarrow] = useState(matches);

  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return undefined;
    const media = window.matchMedia(QUERY);
    const update = () => setNarrow(media.matches);
    update();
    media.addEventListener?.('change', update);
    return () => media.removeEventListener?.('change', update);
  }, []);

  return narrow;
}
