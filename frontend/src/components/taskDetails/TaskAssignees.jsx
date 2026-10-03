import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { toast } from 'react-toastify';
import { WipLimitExceededError, assignUserToTask } from '../../services/api';
import useUserAvatar from '../../board/useUserAvatar';
import { hueOf, initialsOf } from '../../board/cardModel';
import Icon from '../Icon';

function AssigneeAvatar({ user }) {
  const url = useUserAvatar(user.id);
  if (url) {
    return <img src={url} alt="" className="assignee-avatar" />;
  }
  return (
    <span className="assignee-avatar assignee-initials" aria-hidden="true" style={{ '--avatar-hue': hueOf(user.id) }}>
      {initialsOf(user.name)}
    </span>
  );
}

function TaskAssignees({ taskId, users, assignedUsers, readOnly, onRequestRemove, onChanged }) {
  const { t } = useTranslation();
  const [selectedUserId, setSelectedUserId] = useState('');

  const handleAssign = async () => {
    if (!selectedUserId) return;

    try {
      await assignUserToTask(taskId, parseInt(selectedUserId));
      setSelectedUserId('');
      await onChanged();
    } catch (error) {
      console.error('Error assigning user:', error);
      if (error instanceof WipLimitExceededError) {
        const assignee = users.find(user => String(user.id) === String(selectedUserId));
        toast.error(t('notifications.userWipLimitExceeded', {
          name: assignee?.name || assignee?.email || selectedUserId,
          limit: error.status?.wipLimit
        }));
      } else {
        toast.error(t('notifications.userAssignError'));
      }
    }
  };

  const assignable = users.filter(user => !assignedUsers.some(assigned => assigned.id === user.id));

  return (
    <div className="user-assignment-section">
      {assignedUsers.length > 0 && (
        <ul className="assigned-users-grid">
          {assignedUsers.map(user => (
            <li key={user.id} className="assigned-user-card">
              <AssigneeAvatar user={user} />
              <span className="user-name">{user.name}</span>
              {!readOnly && (
                <button
                  type="button"
                  className="remove-user-btn-card"
                  onClick={() => onRequestRemove(user)}
                  aria-label={`${t('forms.deleteUser')}: ${user.name}`}
                  title={t('forms.deleteUser')}
                >
                  <Icon name="close" size="sm" />
                </button>
              )}
            </li>
          ))}
        </ul>
      )}

      {!readOnly && assignable.length > 0 && (
        <div className="assignment-controls">
          <select
            value={selectedUserId}
            onChange={(e) => setSelectedUserId(e.target.value)}
            className="user-select"
            aria-label={t('taskDetails.assignNewUser')}
          >
            <option value="">{t('forms.wipLimit.selectUser')}</option>
            {assignable.map(user => (
              <option key={user.id} value={user.id}>
                {user.name}
              </option>
            ))}
          </select>
          <button
            type="button"
            onClick={handleAssign}
            disabled={!selectedUserId}
            className="assign-btn-relationships"
          >
            {t('taskActions.assign')}
          </button>
        </div>
      )}
    </div>
  );
}

export default TaskAssignees;
