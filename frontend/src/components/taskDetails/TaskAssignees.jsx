import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { toast } from 'react-toastify';
import { WipLimitExceededError, assignUserToTask } from '../../services/api';
import useUserAvatar from '../../board/useUserAvatar';

const DEFAULT_AVATAR = 'data:image/svg+xml,%3Csvg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"%3E%3Cpath d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"%3E%3C/path%3E%3C/svg%3E';

function AssigneeAvatar({ user }) {
  const url = useUserAvatar(user.id);
  return (
    <div className="user-avatar">
      <img
        src={url || DEFAULT_AVATAR}
        alt={`${user.name}'s avatar`}
        className="avatar-preview"
        onError={(e) => {
          e.target.src = DEFAULT_AVATAR;
        }}
      />
    </div>
  );
}

export function AssignedUsersBar({ assignedUsers, readOnly, onRequestRemove }) {
  const { t } = useTranslation();
  if (assignedUsers.length === 0) {
    return null;
  }

  return (
    <div className="assigned-users-bar">
      <span>{t('taskActions.assigned')}:</span>
      <div className="avatar-list">
        {assignedUsers.map(user => (
          <div key={user.id} className="avatar-item" title={user.name}>
            <AssigneeAvatar user={user} />
            {!readOnly && (
              <button
                className="remove-user-btn"
                onClick={() => onRequestRemove(user)}
                title={t('forms.deleteUser')}
              >
                ×
              </button>
            )}
          </div>
        ))}
      </div>
    </div>
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
      <div className="section-header">
        <span className="assignment-icon">
          <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke="currentColor" width="18" height="18">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4.354a4 4 0 110 5.292M15 21H3v-1a6 6 0 0112 0v1zm0 0h6v-1a6 6 0 00-9-5.197M13 7a4 4 0 11-8 0 4 4 0 018 0z" />
          </svg>
        </span>
        <h4>{t('taskActions.assignUser')}</h4>
      </div>

      {assignedUsers.length > 0 && (
        <div className="current-assignments">
          <h5>{t('taskDetails.currentlyAssigned')}</h5>
          <div className="assigned-users-grid">
            {assignedUsers.map(user => (
              <div key={user.id} className="assigned-user-card">
                <AssigneeAvatar user={user} />
                <span className="user-name">{user.name}</span>
                {!readOnly && (
                  <button
                    className="remove-user-btn-card"
                    onClick={() => onRequestRemove(user)}
                    title={t('forms.deleteUser')}
                  >
                    ×
                  </button>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {!readOnly && (
        <div className="add-assignment">
          <h5>{t('taskDetails.assignNewUser')}</h5>
          <div className="assignment-controls">
            <select
              value={selectedUserId}
              onChange={(e) => setSelectedUserId(e.target.value)}
              className="user-select"
            >
              <option value="">{t('forms.wipLimit.selectUser')}</option>
              {assignable.map(user => (
                <option key={user.id} value={user.id}>
                  {user.name}
                </option>
              ))}
            </select>
            <button
              onClick={handleAssign}
              disabled={!selectedUserId}
              className="assign-btn-relationships"
            >
              {t('taskActions.assign')}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

export default TaskAssignees;
