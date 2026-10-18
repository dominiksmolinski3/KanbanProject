import { useCallback, useEffect, useRef, useState } from 'react';

const SAVED_NOTICE_MS = 3000;

export function useSavedNotice() {
  const [saved, setSaved] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => () => {
    if (timer.current) clearTimeout(timer.current);
  }, []);

  const flashSaved = useCallback(() => {
    setSaved(true);
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => setSaved(false), SAVED_NOTICE_MS);
  }, []);

  return { saved, flashSaved };
}
