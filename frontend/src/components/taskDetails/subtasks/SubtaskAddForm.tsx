import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { NAME_MAX_LENGTH } from '../../../services/textLimits';
import Icon from '../../Icon';
import LengthHint from '../../LengthHint';

export default function SubtaskAddForm({ onAdd }: { onAdd: (title: string) => Promise<boolean> }) {
  const { t } = useTranslation();
  const [title, setTitle] = useState('');

  const add = async () => {
    if (!title.trim()) return;
    if (await onAdd(title.trim())) {
      setTitle('');
    }
  };

  return (
    <div className="add-subtask-form">
      <input
        type="text"
        value={title}
        maxLength={NAME_MAX_LENGTH}
        onChange={(e) => setTitle(e.target.value)}
        placeholder={t('taskActions.shadowDescription')}
        className="subtask-input"
      />
      <button onClick={add} disabled={!title.trim()} className="add-subtask-btn">
        <Icon name="plus" size="sm" />
        {t('header.addTask')}
      </button>
      <LengthHint value={title} max={NAME_MAX_LENGTH} />
    </div>
  );
}
