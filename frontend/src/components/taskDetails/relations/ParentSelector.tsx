import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { Task } from '../../../types/api';

interface ParentSelectorProps {
  candidates: Task[];
  onAssign: (parentId: number) => void;
  onCancel: () => void;
}

export default function ParentSelector({ candidates, onAssign, onCancel }: ParentSelectorProps) {
  const { t } = useTranslation();
  const [selectedId, setSelectedId] = useState('');

  return (
    <div className="parent-selector-card">
      <select
        value={selectedId}
        onChange={(e) => setSelectedId(e.target.value)}
        className="parent-select"
        aria-label={t('taskDetails.selectParentTask')}
      >
        <option value="">{t('taskDetails.chooseTask')}</option>
        {candidates.map(candidate => (
          <option key={candidate.id} value={candidate.id}>
            {candidate.title}
          </option>
        ))}
      </select>
      <div className="parent-selector-actions">
        <button
          type="button"
          onClick={() => onAssign(parseInt(selectedId))}
          disabled={!selectedId}
          className="confirm-parent-btn"
        >
          {t('taskActions.assign')}
        </button>
        <button type="button" onClick={onCancel} className="cancel-parent-btn">
          {t('taskActions.cancel')}
        </button>
      </div>
    </div>
  );
}
