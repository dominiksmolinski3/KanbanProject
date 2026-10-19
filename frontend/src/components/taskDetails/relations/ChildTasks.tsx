import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { getChildTasks } from '../../../services/api';
import type { Id, Task } from '../../../types/api';

export default function ChildTasks({ taskId }: { taskId: Id }) {
  const { t } = useTranslation();
  const [children, setChildren] = useState<Task[]>([]);

  useEffect(() => {
    let live = true;
    (async () => {
      try {
        const data = await getChildTasks(taskId);
        if (live) setChildren(data || []);
      } catch (error) {
        console.error('Error fetching child tasks:', error);
        if (live) setChildren([]);
      }
    })();
    return () => {
      live = false;
    };
  }, [taskId]);

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
