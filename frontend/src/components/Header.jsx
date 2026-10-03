import { Link, NavLink } from 'react-router-dom';
import React, { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import LanguageSwitcher from './LanguageSwitcher';
import ThemeSwitcher from './ThemeSwitcher';
import BoardSwitcher from './BoardSwitcher';
import { useAuth } from '../context/AuthContext';
import BrandMark from './BrandMark';
import Icon from './Icon';

const PAGES = [
  { to: '/board', key: 'header.board', icon: 'board' },
  { to: '/users', key: 'header.users', icon: 'people' },
  { to: '/activity', key: 'header.activity', icon: 'activity' },
  { to: '/flow', key: 'header.flow', icon: 'flow' },
];

function Header() {
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef(null);
  const { logout } = useAuth();
  const { t } = useTranslation();

  const handleLogout = () => {
    logout();
    window.location.href = '/';
  };

  useEffect(() => {
    if (!menuOpen) {
      return undefined;
    }
    const closeOnOutsideClick = (event) => {
      if (menuRef.current && !menuRef.current.contains(event.target)) {
        setMenuOpen(false);
      }
    };
    const closeOnEscape = (event) => {
      if (event.key === 'Escape') {
        setMenuOpen(false);
      }
    };
    document.addEventListener('mousedown', closeOnOutsideClick);
    document.addEventListener('keydown', closeOnEscape);
    return () => {
      document.removeEventListener('mousedown', closeOnOutsideClick);
      document.removeEventListener('keydown', closeOnEscape);
    };
  }, [menuOpen]);

  return (
    <header className="app-header">
      <Link to="/" className="app-brand" aria-label={t('board.title')}>
        <BrandMark className="app-logo" />
        <span className="app-title">{t('board.title')}</span>
      </Link>

      <BoardSwitcher />

      <nav className="header-nav" aria-label={t('header.navigation')}>
        {PAGES.map(page => (
          <NavLink key={page.to} to={page.to} className="nav-link" title={t(page.key)}>
            <Icon name={page.icon} />
            <span className="nav-label">{t(page.key)}</span>
          </NavLink>
        ))}
      </nav>

      <div className="header-menu" ref={menuRef}>
        <button
          type="button"
          className="nav-link header-menu-toggle"
          aria-haspopup="true"
          aria-expanded={menuOpen}
          aria-label={t('header.settings')}
          title={t('header.settings')}
          data-testid="header-menu-toggle"
          onClick={() => setMenuOpen(!menuOpen)}
        >
          <Icon name="settings" />
        </button>

        {menuOpen && (
          <div className="header-menu-panel">
            <div className="header-menu-row">
              <span className="header-menu-label">{t('header.language')}</span>
              <LanguageSwitcher />
            </div>
            <div className="header-menu-row">
              <span className="header-menu-label">{t('header.theme')}</span>
              <ThemeSwitcher />
            </div>
            <Link to="/account" className="header-menu-item" onClick={() => setMenuOpen(false)}>
              <Icon name="user" />
              {t('header.account')}
            </Link>
            <Link to="/sessions" className="header-menu-item" onClick={() => setMenuOpen(false)}>
              <Icon name="laptop" />
              {t('header.sessions')}
            </Link>
            <button type="button" className="header-menu-item logout-btn" onClick={handleLogout}>
              <Icon name="logout" />
              {t('header.logout')}
            </button>
          </div>
        )}
      </div>
    </header>
  );
}

export default Header;
