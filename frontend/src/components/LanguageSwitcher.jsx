import React, { useState, useRef, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../context/AuthContext';
import { updateUserLocale } from '../services/api';
import '../styles/components/LanguageSwitcher.css';

function LanguageSwitcher() {
  const { i18n } = useTranslation();
  // Optional chaining rather than a destructure: nothing about a language control should
  // throw when it is rendered without an auth context around it.
  const user = useAuth()?.user;
  const [isOpen, setIsOpen] = useState(false);
  const dropdownRef = useRef(null);

  const languages = [
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

  const changeLanguage = (e, lng) => {
    e.preventDefault();
    e.stopPropagation();
    i18n.changeLanguage(lng);
    setIsOpen(false);

    // The switcher is the language control, so it sets the language of the mail too. Mail is the
    // one place the client is not there to pick - a verification code is composed by a route and a
    // deadline notice by a scheduler - so the account has to carry the answer, and this is where a
    // person says it. Signed out there is no account to say it to; signup sends the same value.
    if (!user?.id) {
      return;
    }
    // Not awaited and not toasted: what was asked for has already happened on screen, and this is
    // a preference for a message that has not been written yet.
    updateUserLocale(user.id, lng).catch((error) => {
      console.error('Could not store the account language', error);
    });
  };

  useEffect(() => {
    function handleClickOutside(event) {
      if (dropdownRef.current && !dropdownRef.current.contains(event.target)) {
        setIsOpen(false);
      }
    }
    
    document.addEventListener('mousedown', handleClickOutside);
    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
    };
  }, []);

  const toggleDropdown = (e) => {
    e.preventDefault();
    e.stopPropagation();
    setIsOpen(!isOpen);
  };

  const getCurrentLanguageLabel = () => {
    const currentLang = languages.find(lang => 
      i18n.language.startsWith(lang.code)
    );
    return currentLang ? currentLang.label : 'English';
  };

  return (
    <div className="language-switcher" ref={dropdownRef}>
      <button 
        className="language-switcher-button"
        onClick={toggleDropdown}
        aria-haspopup="true"
        aria-expanded={isOpen}
        type="button"
      >
        <span className="current-language">{getCurrentLanguageLabel()}</span>
        <span className="dropdown-arrow">{isOpen ? '▲' : '▼'}</span>
      </button>
      
      {isOpen && (
        <div className="language-dropdown">
          {languages.map((lang) => (
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