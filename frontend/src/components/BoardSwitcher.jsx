import React, { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useKanban } from '../context/KanbanContext';
import '../styles/components/BoardSwitcher.css';

/**
 * Which board the session is looking at.
 *
 * <p>This exists because membership does: the moment somebody invites you, you have two boards -
 * your own and theirs - and the client can no longer let the server pick one. It is deliberately
 * only a switcher; renaming, deleting and the member list live on the board page, where there is
 * room to explain what they do.
 */
function BoardSwitcher() {
  const { boards, activeBoard, activeBoardId, selectBoard, createBoard } = useKanban();
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

  // One board and nothing to switch to is the common case; a dropdown there would be noise.
  if (!activeBoard && boards.length === 0) {
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
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 5h4v14H4zM10 5h4v9h-4zM16 5h4v6h-4z" />
        </svg>
        {activeBoard ? activeBoard.name : t('boards.switcher.label')}
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
