import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { THEME_PREFERENCES, readThemePreference, writeThemePreference } from '../theme/themePreference';
import '../styles/components/BoardTokens.css';
import '../styles/components/ThemeSwitcher.css';

const LABEL_KEYS = {
  system: 'header.themeSystem',
  light: 'header.themeLight',
  dark: 'header.themeDark'
};

function ThemeSwitcher() {
  const { t } = useTranslation();
  const [preference, setPreference] = useState(readThemePreference);

  const choose = (next) => {
    setPreference(next);
    writeThemePreference(next);
  };

  return (
    <div className="theme-switcher" role="group" aria-label={t('header.theme')}>
      {THEME_PREFERENCES.map((option) => (
        <button
          key={option}
          type="button"
          className={`theme-switcher-option${preference === option ? ' active' : ''}`}
          aria-pressed={preference === option}
          onClick={() => choose(option)}
        >
          {t(LABEL_KEYS[option])}
        </button>
      ))}
    </div>
  );
}

export default ThemeSwitcher;
