import type { CSSProperties } from 'react';
import { useTranslation } from 'react-i18next';
import { hueOf, initialsOf } from '../../board/cardModel';
import type { Id, User } from '../../types/api';
import Icon from '../Icon';
import { AVATAR_ACCEPT } from './usePeople';

interface PersonRowProps {
  person: User;
  avatar: string | undefined;
  isMe: boolean;
  onUpload: (userId: Id, file: File) => void;
  onDelete: (userId: Id) => void;
}

export default function PersonRow({ person, avatar, isMe, onUpload, onDelete }: PersonRowProps) {
  const { t } = useTranslation();

  return (
    <li className="user-item" data-user-id={person.id}>
      {avatar ? (
        <img src={avatar} alt="" className="people-avatar" />
      ) : (
        <span className="people-avatar" aria-hidden="true" style={{ '--avatar-hue': hueOf(person.id) } as CSSProperties}>
          {initialsOf(person.name || person.email)}
        </span>
      )}
      <span className="people-identity">
        <span className="user-name">
          {person.name}
          {isMe && <span className="people-you">{t('boards.members.you')}</span>}
        </span>
        <span className="user-email" dir="ltr">{person.email}</span>
      </span>
      {isMe && (
        <span className="user-actions">
          <label className="btn btn-secondary btn-sm people-avatar-change">
            {t('account.avatar.change')}
            <input
              id={`avatar-input-${person.id}`}
              type="file"
              accept={AVATAR_ACCEPT}
              onChange={(e) => {
                const file = e.target.files?.[0];
                e.target.value = '';
                if (file) {
                  onUpload(person.id, file);
                }
              }}
            />
          </label>
          <button
            type="button"
            className="delete-user-btn"
            title={t('usersManagement.buttons.delete')}
            aria-label={t('usersManagement.buttons.delete')}
            onClick={() => onDelete(person.id)}
          >
            <Icon name="trash" size="sm" />
          </button>
        </span>
      )}
    </li>
  );
}
