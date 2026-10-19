import type { DragEvent } from 'react';
import { useTranslation } from 'react-i18next';
import type { Id } from '../../types/api';
import UserWipLimit from './UserWipLimit';
import type { BenchUser } from './useBenchUsers';

const PLACEHOLDER_AVATAR = 'data:image/svg+xml,%3Csvg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"%3E%3Cpath d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"%3E%3C/path%3E%3C/svg%3E';

interface BenchUserCardProps {
  user: BenchUser;
  avatar: string | undefined;
  onSaveWipLimit: (userId: Id, limit: number | null) => Promise<boolean>;
}

export default function BenchUserCard({ user, avatar, onSaveWipLimit }: BenchUserCardProps) {
  const { t } = useTranslation();

  const onDragStart = (e: DragEvent<HTMLDivElement>) => {
    e.dataTransfer.setData('application/user', JSON.stringify({
      userId: user.id,
      type: 'user',
      userName: user.name
    }));
    e.dataTransfer.effectAllowed = 'copy';
  };

  return (
    <div className="user-card" draggable="true" onDragStart={onDragStart}>
      <div className="user-avatar">
        <img
          src={avatar || PLACEHOLDER_AVATAR}
          alt={`${user.name} avatar`}
          className="avatar"
          onError={(e) => {
            e.currentTarget.src = PLACEHOLDER_AVATAR;
          }}
        />
      </div>
      <div className="user-info">
        <div className="user-name">{user.name}</div>
        {user.role && <div className="user-role">{user.role}</div>}
        <UserWipLimit user={user} onSave={onSaveWipLimit} />
        {user.taskCount ? (
          <div className={`user-task-count ${(user.taskCount >= (user.wipLimit || Infinity)) ? 'limit-reached' : ''}`}>
            {t('bench.tasks')}: {user.taskCount}/{user.wipLimit || '∞'}
          </div>
        ) : null}
      </div>
    </div>
  );
}
