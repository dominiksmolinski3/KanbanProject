import { useEffect, useState } from 'react';

type DocumentTheme = 'light' | 'dark';

const readTheme = (): DocumentTheme => (document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light');

export function useDocumentTheme(): DocumentTheme {
  const [theme, setTheme] = useState(readTheme);

  useEffect(() => {
    const observer = new MutationObserver(() => setTheme(readTheme()));
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
    setTheme(readTheme());
    return () => observer.disconnect();
  }, []);

  return theme;
}
