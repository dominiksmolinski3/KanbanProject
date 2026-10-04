import { useState, useEffect, useCallback, useRef } from 'react';
import '../styles/components/Users.css';
import { toast } from 'react-toastify';
import { useTranslation } from 'react-i18next';
import BoardMembers from './BoardMembers';
import Invitations from './Invitations';
import Icon from './Icon';
import { useAuth } from '../context/AuthContext';
import { hueOf, initialsOf } from '../board/cardModel';

function UsersManagement() {
  const [users, setUsers] = useState([]);
  const [avatarPreviews, setAvatarPreviews] = useState({});
  const avatarPreviewsRef = useRef({});
  const { t } = useTranslation();
  const { user: currentUser } = useAuth();

  useEffect(() => {
    avatarPreviewsRef.current = avatarPreviews;
  }, [avatarPreviews]);

  const fetchUserAvatar = useCallback(async (userId) => {
    try {
      const response = await fetch(`/api/users/${userId}/avatar`, {
        headers: {
          'Accept': 'image/*, application/json',
          'Cache-Control': 'no-cache'
        }
      });
      
      if (!response.ok) {
        throw new Error('Failed to fetch avatar');
      }
      
      const blob = await response.blob();
      return URL.createObjectURL(blob);
    } catch (error) {
      console.warn(`Failed to load avatar for user ${userId}:`, error);
      return null;
    }
  }, []);

  const loadUsers = useCallback(async () => {
    try {
      const response = await fetch('/api/users');
      if (!response.ok) {
        throw new Error('Failed to fetch users');
      }
      
      const data = await response.json();
      setUsers(data);
  
      const avatarPromises = data.map(async (user) => {
        const avatarUrl = await fetchUserAvatar(user.id);
        if (avatarUrl) {
          setAvatarPreviews(prev => ({
            ...prev,
            [user.id]: avatarUrl
          }));
        }
      });
  
      await Promise.all(avatarPromises);
    } catch (error) {
      console.error(t('usersManagement.messages.loadError'), error);
      toast.error(t('usersManagement.messages.loadError'));
    }
  }, [fetchUserAvatar, t]);

  useEffect(() => {
    loadUsers();
    
    return () => {
      Object.values(avatarPreviewsRef.current).forEach(url => {
        URL.revokeObjectURL(url);
      });
    };
  }, [loadUsers]);
  
  const handleAvatarUpload = async (userId, file) => {
    const MAX_FILE_SIZE = 1024 * 1024;
    const ALLOWED_TYPES = ['image/jpeg', 'image/png', 'image/webp', 'image/gif'];
  
    try {
      if (file.size > MAX_FILE_SIZE) {
        toast.info(t('usersManagement.messages.fileTooLarge'));
        return;
      }
  
      if (!ALLOWED_TYPES.includes(file.type)) {
        toast.info(t('usersManagement.messages.fileTypeError'));
        return;
      }
  
      const formData = new FormData();
      formData.append('file', file);
  
      const response = await fetch(`/api/users/${userId}/avatar`, {
        method: 'POST',
        body: formData,
        headers: {
          'Accept': 'application/json',
        },
        credentials: 'include'
      });
  
      const responseData = await response.text();
      
      if (!response.ok) {
        throw new Error(responseData || 'Failed to upload avatar');
      }
  
      let retries = 3;
      while (retries > 0) {
        try {
          const avatarResponse = await fetch(`/api/users/${userId}/avatar`, {
            headers: {
              'Cache-Control': 'no-cache',
              'Accept': 'image/*, application/json'
            }
          });
          
          if (avatarResponse.ok) {
            const blob = await avatarResponse.blob();
            if (avatarPreviews[userId]) {
              URL.revokeObjectURL(avatarPreviews[userId]);
            }
            const imageUrl = URL.createObjectURL(blob);
            setAvatarPreviews(prev => ({
              ...prev,
              [userId]: imageUrl
            }));
            break;
          }
          retries--;
          await new Promise(resolve => setTimeout(resolve, 1000));
        } catch (error) {
          console.warn('Retry failed:', error);
          retries--;
          if (retries === 0) throw error;
        }
      }
  
      toast.info(t('usersManagement.messages.avatarUpdated'));
    } catch (error) {
      console.error('Error uploading avatar:', error);
      toast.warning(`${t('usersManagement.messages.avatarError')} ${error.message}`);
    }
  };
  
  const renderUserAvatar = (user) => (
    avatarPreviews[user.id] ? (
      <img src={avatarPreviews[user.id]} alt="" className="people-avatar" />
    ) : (
      <span className="people-avatar" aria-hidden="true" style={{ '--avatar-hue': hueOf(user.id) }}>
        {initialsOf(user.name || user.email)}
      </span>
    )
  );

  const deleteUser = (userId) => {
    if (window.confirm(t('usersManagement.messages.deleteConfirm'))) {
      fetch(`/api/users/${userId}`, {
        method: 'DELETE'
      })
        .then(response => {
          if (response.ok || response.status === 404) {
            setUsers(users.filter(user => user.id !== userId));
          } else {
            throw new Error(t('usersManagement.messages.deleteError'));
          }
        })
        .catch(error => {
          console.error('Error:', error);
          toast.error(t('usersManagement.messages.deleteError'));
        });
    }
  };

  const isMe = (person) => currentUser?.id != null && person.id === currentUser.id;
  const people = [...users].sort((a, b) => Number(isMe(b)) - Number(isMe(a)));

  return (
    <div className="page-shell users-page">
      <div className="page-head">
        <h1 className="page-title">{t('usersManagement.title')}</h1>
        <p className="page-lede">{t('usersManagement.intro')}</p>
      </div>

      <Invitations />

      <BoardMembers />

      <section className="page-panel people-panel" aria-labelledby="people-heading">
        <h2 className="page-panel-title" id="people-heading">{t('usersManagement.people')}</h2>
        <ul id="usersList" className="users-list">
          {people.map(user => (
            <li key={user.id} className="user-item" data-user-id={user.id}>
              {renderUserAvatar(user)}
              <span className="people-identity">
                <span className="user-name">
                  {user.name}
                  {isMe(user) && <span className="people-you">{t('boards.members.you')}</span>}
                </span>
                <span className="user-email" dir="ltr">{user.email}</span>
              </span>
              {isMe(user) && (
                <span className="user-actions">
                  <label className="btn btn-secondary btn-sm people-avatar-change">
                    {t('account.avatar.change')}
                    <input
                      id={`avatar-input-${user.id}`}
                      type="file"
                      accept="image/jpeg,image/png,image/webp,image/gif"
                      onChange={(e) => {
                        const file = e.target.files[0];
                        e.target.value = '';
                        if (file) {
                          handleAvatarUpload(user.id, file);
                        }
                      }}
                    />
                  </label>
                  <button
                    type="button"
                    className="delete-user-btn"
                    title={t('usersManagement.buttons.delete')}
                    aria-label={t('usersManagement.buttons.delete')}
                    onClick={() => deleteUser(user.id)}
                  >
                    <Icon name="trash" size="sm" />
                  </button>
                </span>
              )}
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}

export default UsersManagement;
