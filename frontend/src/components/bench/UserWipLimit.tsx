import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { Id } from '../../types/api';
import type { BenchUser } from './useBenchUsers';

interface UserWipLimitProps {
  user: BenchUser;
  onSave: (userId: Id, limit: number | null) => Promise<boolean>;
}

export default function UserWipLimit({ user, onSave }: UserWipLimitProps) {
  const { t } = useTranslation();
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState<number | null>(user.wipLimit || null);

  const save = async () => {
    if (await onSave(user.id, draft)) {
      setEditing(false);
    }
  };

  const cancel = () => {
    setDraft(user.wipLimit || null);
    setEditing(false);
  };

  return (
    <div className="user-wip-limit">
      {editing ? (
        <div className="wip-limit-editor">
          <input
            type="number"
            min="1"
            value={draft || ''}
            placeholder="∞"
            onChange={(e) => setDraft(e.target.value === '' ? null : parseInt(e.target.value))}
            onClick={(e) => e.stopPropagation()}
          />
          <button
            className="save-wip-btn"
            onClick={(e) => {
              e.stopPropagation();
              save();
            }}
            title={t('bench.saveWipLimit')}
          >
            ✓
          </button>
          <button
            className="cancel-wip-btn"
            onClick={(e) => {
              e.stopPropagation();
              cancel();
            }}
            title={t('bench.cancel')}
          >
            ✗
          </button>
        </div>
      ) : (
        <div
          className="wip-limit-display"
          onClick={(e) => {
            e.stopPropagation();
            setEditing(true);
          }}
        >
          <span>{t('bench.wipLimit')}: {user.wipLimit ? user.wipLimit : '∞'}</span>
          <button className="edit-wip-btn" title={t('bench.editWipLimit')}>
            ✎
          </button>
        </div>
      )}
    </div>
  );
}
