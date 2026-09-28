import { useEffect, useState } from 'react';

const readTheme = () => (document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light');

export function useDocumentTheme() {
  const [theme, setTheme] = useState(readTheme);

  useEffect(() => {
    const observer = new MutationObserver(() => setTheme(readTheme()));
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
    setTheme(readTheme());
    return () => observer.disconnect();
  }, []);

  return theme;
}
