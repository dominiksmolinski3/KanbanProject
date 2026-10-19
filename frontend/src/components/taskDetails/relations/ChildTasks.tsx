import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { getChildTasks } from '../../../services/api';
import { logged } from '../../../services/logged';
import { taskKeys } from '../../../services/taskQueries';
import type { Id, Task } from '../../../types/api';

const NONE: Task[] = [];

export default function ChildTasks({ taskId }: { taskId: Id }) {
  const { t } = useTranslation();
  const { data } = useQuery({
    queryKey: taskKeys.children(taskId),
    queryFn: async () => (await logged('Error fetching child tasks:', () => getChildTasks(taskId))) || NONE,
  });
  const children = data ?? NONE;

  return (
    <section className="child-tasks-section">
      <div className="section-header">
        <h3>{t('taskDetails.childTasks')}</h3>
        {children.length > 0 && <span className="section-meta">{children.length}</span>}
      </div>

      {children.length > 0 ? (
        <ul className="child-tasks-grid">
          {children.map(child => (
            <li key={child.id} className="child-task-card">
              <span className="child-task-info">{child.title}</span>
            </li>
          ))}
        </ul>
      ) : (
        <p className="no-children-card">{t('taskDetails.noChildTasks')}</p>
      )}
    </section>
  );
}
