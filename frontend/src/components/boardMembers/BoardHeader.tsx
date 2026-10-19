import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { NAME_MAX_LENGTH } from '../../services/textLimits';
import type { Board } from '../../types/api';
import Icon from '../Icon';

interface BoardHeaderProps {
  board: Board;
  owned: boolean;
  busy: boolean;
  onRename: (name: string) => Promise<boolean>;
}

export default function BoardHeader({ board, owned, busy, onRename }: BoardHeaderProps) {
  const { t } = useTranslation();
  const [renaming, setRenaming] = useState(false);
  const [name, setName] = useState('');

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const trimmed = name.trim();
    if (!trimmed || busy) {
      return;
    }
    if (await onRename(trimmed)) {
      setRenaming(false);
    }
  };

  if (renaming) {
    return (
      <form className="board-rename" onSubmit={submit}>
        <label className="visually-hidden" htmlFor="board-rename-input">{t('boards.members.rename')}</label>
        <input
          id="board-rename-input"
          className="field-input"
          type="text"
          value={name}
          autoFocus
          maxLength={NAME_MAX_LENGTH}
          onChange={(event) => setName(event.target.value)}
        />
        <button type="submit" className="btn btn-primary btn-sm">{t('boards.members.save')}</button>
        <button type="button" className="btn btn-secondary btn-sm" onClick={() => setRenaming(false)}>
          {t('boards.members.cancel')}
        </button>
      </form>
    );
  }

  return (
    <header className="board-panel-header">
      <h2 className="page-panel-title" id="board-panel-heading">{board.name}</h2>
      <span className={`board-role${owned ? ' owner' : ''}`}>
        {owned ? t('boards.members.youOwnIt') : t('boards.members.sharedWithYou')}
      </span>
      {owned && (
        <button
          type="button"
          className="btn btn-ghost btn-sm board-panel-action"
          onClick={() => {
            setName(board.name);
            setRenaming(true);
          }}
        >
          <Icon name="edit" size="sm" />
          {t('boards.members.rename')}
        </button>
      )}
    </header>
  );
}
