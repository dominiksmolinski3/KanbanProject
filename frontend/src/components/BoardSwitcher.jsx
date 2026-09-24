import React, { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useKanban } from '../context/KanbanContext';
import '../styles/components/BoardSwitcher.css';

function BoardSwitcher() {
  const { boards, activeBoard, activeBoardId, selectBoard, createBoard, myInvitations } = useKanban();
  const [open, setOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const containerRef = useRef(null);
  const { t } = useTranslation();

  useEffect(() => {
    if (!open) {
      return undefined;
    }
    const closeOnOutsideClick = (event) => {
      if (containerRef.current && !containerRef.current.contains(event.target)) {
        setOpen(false);
        setCreating(false);
      }
    };
    document.addEventListener('mousedown', closeOnOutsideClick);
    return () => document.removeEventListener('mousedown', closeOnOutsideClick);
  }, [open]);

  const handleCreate = async (event) => {
    event.preventDefault();
    const trimmed = name.trim();
    if (!trimmed) {
      return;
    }
    const created = await createBoard(trimmed);
    if (created) {
      setName('');
      setCreating(false);
      setOpen(false);
    }
  };

  const pending = myInvitations ? myInvitations.length : 0;

  if (!activeBoard && boards.length === 0 && pending === 0) {
    return null;
  }

  return (
    <div className="board-switcher" ref={containerRef}>
      <button
        type="button"
        className="nav-link board-switcher-toggle"
        aria-haspopup="true"
        aria-expanded={open}
        onClick={() => setOpen(!open)}
      >
        <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke="currentColor" width="20" height="20" style={{ marginRight: '0.5rem', verticalAlign: 'middle' }}>
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10" />
        </svg>
        <span className="board-switcher-name">{activeBoard ? activeBoard.name : t('boards.switcher.label')}</span>
        {pending > 0 && (
          <span
            className="board-switcher-badge"
            data-testid="invitation-badge"
            title={t('boards.invitations.badge', { count: pending })}
          >
            {pending}
          </span>
        )}
        <span className="board-switcher-caret" aria-hidden="true">▾</span>
      </button>

      {open && (
        <div className="board-switcher-menu" role="menu">
          <p className="board-switcher-heading">{t('boards.switcher.heading')}</p>
          <ul className="board-switcher-list">
            {boards.map(board => (
              <li key={board.id}>
                <button
                  type="button"
                  role="menuitem"
                  className={board.id === activeBoardId ? 'board-option active' : 'board-option'}
                  onClick={() => {
                    selectBoard(board.id);
                    setOpen(false);
                  }}
                >
                  <span className="board-option-name">{board.name}</span>
                  {!board.owned && (
                    <span className="board-option-tag">{t('boards.switcher.shared')}</span>
                  )}
                </button>
              </li>
            ))}
          </ul>

          {creating ? (
            <form className="board-switcher-create" onSubmit={handleCreate}>
              <input
                type="text"
                value={name}
                autoFocus
                maxLength={255}
                placeholder={t('boards.switcher.namePlaceholder')}
                onChange={(event) => setName(event.target.value)}
              />
              <button type="submit">{t('boards.switcher.create')}</button>
            </form>
          ) : (
            <button
              type="button"
              className="board-switcher-new"
              onClick={() => setCreating(true)}
            >
              + {t('boards.switcher.newBoard')}
            </button>
          )}
        </div>
      )}
    </div>
  );
}

export default BoardSwitcher;
