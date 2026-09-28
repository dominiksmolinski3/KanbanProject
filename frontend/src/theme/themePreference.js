export const THEME_STORAGE_KEY = 'kanban-theme';
export const THEME_CHANGE_EVENT = 'kanban-theme-change';
export const THEME_PREFERENCES = ['system', 'light', 'dark'];

export function readThemePreference() {
  try {
    const stored = window.localStorage.getItem(THEME_STORAGE_KEY);
    return stored === 'light' || stored === 'dark' ? stored : 'system';
  } catch {
    return 'system';
  }
}

export function writeThemePreference(preference) {
  try {
    if (preference === 'light' || preference === 'dark') {
      window.localStorage.setItem(THEME_STORAGE_KEY, preference);
    } else {
      window.localStorage.removeItem(THEME_STORAGE_KEY);
    }
  } catch {
    // Storage can be off (private mode); the choice then lasts for this page only.
  }
  window.dispatchEvent(new Event(THEME_CHANGE_EVENT));
}
