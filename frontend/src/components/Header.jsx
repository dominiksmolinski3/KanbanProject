import { Link, NavLink } from 'react-router-dom';
import React, { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import LanguageSwitcher from './LanguageSwitcher';
import BoardSwitcher from './BoardSwitcher';
import { useAuth } from '../context/AuthContext';

const ICON_PROPS = {
  xmlns: 'http://www.w3.org/2000/svg',
  fill: 'none',
  viewBox: '0 0 24 24',
  stroke: 'currentColor',
  width: 20,
  height: 20,
  'aria-hidden': true,
};

const PAGES = [
  { to: '/board', key: 'header.board', icon: 'M4 5h4v14H4zM10 5h4v9h-4zM16 5h4v6h-4z' },
  { to: '/users', key: 'header.users', icon: 'M12 4.354a4 4 0 110 5.292M15 21H3v-1a6 6 0 0112 0v1zm0 0h6v-1a6 6 0 00-9-5.197M13 7a4 4 0 11-8 0 4 4 0 018 0z' },
  { to: '/activity', key: 'header.activity', icon: 'M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z' },
  { to: '/flow', key: 'header.flow', icon: 'M3 3v18h18M7 15l4-4 3 3 5-6' },
];

function Header() {
  const [isSticky, setIsSticky] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef(null);
  const { logout } = useAuth();
  const { t } = useTranslation();

  const handleLogout = () => {
    logout();
    window.location.href = '/';
  };

  useEffect(() => {
    const handleScroll = () => {
      setIsSticky(window.scrollY > 0);
    };

    window.addEventListener('scroll', handleScroll);

    return () => {
      window.removeEventListener('scroll', handleScroll);
    };
  }, []);

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
    <header className={`app-header ${isSticky ? 'sticky' : ''}`}>
      <Link to="/" className="app-brand">
        <img src="/kanban-logo.png" alt={t('header.logoAlt')} className="app-logo" />
        <h1 className="app-title">{t('board.title')}</h1>
      </Link>

      <BoardSwitcher />

      <nav className="header-nav" aria-label={t('header.navigation')}>
        {PAGES.map(page => (
          <NavLink key={page.to} to={page.to} className="nav-link" title={t(page.key)}>
            <svg {...ICON_PROPS}>
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d={page.icon} />
            </svg>
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
          <svg {...ICON_PROPS}>
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
          </svg>
        </button>

        {menuOpen && (
          <div className="header-menu-panel">
            <div className="header-menu-row">
              <span className="header-menu-label">{t('header.language')}</span>
              <LanguageSwitcher />
            </div>
            <Link to="/sessions" className="header-menu-item" onClick={() => setMenuOpen(false)}>
              <svg {...ICON_PROPS}>
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
              </svg>
              {t('header.sessions')}
            </Link>
            <button type="button" className="header-menu-item logout-btn" onClick={handleLogout}>
              <svg {...ICON_PROPS}>
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1" />
              </svg>
              {t('header.logout')}
            </button>
          </div>
        )}
      </div>
    </header>
  );
}

export default Header;
