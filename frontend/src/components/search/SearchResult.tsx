import { useTranslation } from 'react-i18next';
import type { Id, Task } from '../../types/api';

interface SearchResultProps {
  task: Task;
  cell: string;
  userName: (userId: Id) => string;
  missing: boolean;
  onShow: (taskId: Id) => void;
}

export default function SearchResult({ task, cell, userName, missing, onShow }: SearchResultProps) {
  const { t } = useTranslation();

  return (
    <li className="task-search-result">
      <div className="task-search-result-main">
        <span className={`task-search-result-title ${task.completed ? 'completed' : ''}`}>
          {task.title}
        </span>
        <span className="task-search-result-cell">{cell}</span>
      </div>
      <div className="task-search-result-meta">
        {(task.labels || []).map((label) => (
          <span key={label} className="task-search-result-label">
            {label}
          </span>
        ))}
        {(task.userIds || []).map((userId) => (
          <span key={userId} className="task-search-result-user">
            {userName(userId)}
          </span>
        ))}
        {task.deadline && (
          <span className={`task-search-result-deadline ${task.expired ? 'expired' : ''}`}>
            {task.deadline.replace('T', ' ').slice(0, 16)}
          </span>
        )}
      </div>
      <button type="button" className="task-search-locate" onClick={() => onShow(task.id)}>
        {t('board.search.showOnBoard')}
      </button>
      {missing && (
        <span className="task-search-missing">{t('board.search.notOnBoard')}</span>
      )}
    </li>
  );
}
