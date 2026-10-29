import { useState, useRef, type MouseEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../context/AuthContext';
import { useOutsideClick } from '../hooks/useOutsideClick';
import { updateUserLocale } from '../services/api';
import '../styles/components/LanguageSwitcher.css';

const LANGUAGES = [
  { code: 'en', label: 'English' },
  { code: 'pl', label: 'Polski' },
  { code: 'de', label: 'Deutsch' },
  { code: 'es', label: 'Español' },
  { code: 'fr', label: 'Français' },
  { code: 'it', label: 'Italiano' },
  { code: 'ja', label: '日本語' },
  { code: 'ru', label: 'Русский' },
  { code: 'ar', label: 'العربية' }
];

function LanguageSwitcher() {
  const { i18n } = useTranslation();
  const user = useAuth()?.user;
  const [isOpen, setIsOpen] = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);

  useOutsideClick(dropdownRef, isOpen, () => setIsOpen(false));

  const changeLanguage = (e: MouseEvent, lng: string) => {
    e.preventDefault();
    e.stopPropagation();
    i18n.changeLanguage(lng);
    setIsOpen(false);

    if (!user?.id) {
      return;
    }
    updateUserLocale(user.id, lng).catch((error) => {
      console.error('Could not store the account language', error);
    });
  };

  const toggleDropdown = (e: MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setIsOpen(!isOpen);
  };

  const current = LANGUAGES.find(lang => i18n.language.startsWith(lang.code)) ?? LANGUAGES[0];

  return (
    <div className="language-switcher" ref={dropdownRef}>
      <button
        className="language-switcher-button"
        onClick={toggleDropdown}
        aria-expanded={isOpen}
        type="button"
      >
        <span className="current-language">{current.label}</span>
        <span className="dropdown-arrow">{isOpen ? '▲' : '▼'}</span>
      </button>

      {isOpen && (
        <div className="language-dropdown">
          {LANGUAGES.map((lang) => (
            <button
              key={lang.code}
              onClick={(e) => changeLanguage(e, lang.code)}
              className={i18n.language.startsWith(lang.code) ? 'active' : ''}
              type="button"
            >
              {lang.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

export default LanguageSwitcher;
