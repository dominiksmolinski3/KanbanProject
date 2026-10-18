import type { CSSProperties } from 'react';
import { useTranslation } from 'react-i18next';
import useUserAvatar from '../board/useUserAvatar';
import { hueOf, initialsOf } from '../board/cardModel';
import type { BoardMember, Id } from '../types/api';

const MAX_VISIBLE = 3;

function Avatar({ userId, name }: { userId: Id; name?: string }) {
  const { t } = useTranslation();
  const url = useUserAvatar(userId);
  const label = name || t('taskActions.avatarAlt');

  if (url) {
    return (
      <img
        src={url}
        alt={t('taskActions.avatarAlt')}
        title={label}
        className="avatar-preview avatar-stack-item"
      />
    );
  }

  return (
    <span
      className="avatar-preview avatar-stack-item avatar-initials"
      role="img"
      aria-label={label}
      title={label}
      style={{ '--avatar-hue': hueOf(userId) } as CSSProperties}
    >
      {initialsOf(name)}
    </span>
  );
}

interface AvatarStackProps {
  userIds?: Id[];
  members?: Pick<BoardMember, 'id' | 'name'>[];
}

function AvatarStack({ userIds = [], members = [] }: AvatarStackProps) {
  const { t } = useTranslation();
  if (userIds.length === 0) return null;

  const nameOf = (id: Id) => members.find((member) => String(member.id) === String(id))?.name;
  const visible = userIds.slice(0, MAX_VISIBLE);
  const hidden = userIds.length - visible.length;

  return (
    <div className="task-avatar avatar-stack" role="group" aria-label={t('taskActions.assignees', { n: userIds.length })}>
      {visible.map((id) => (
        <Avatar key={id} userId={id} name={nameOf(id)} />
      ))}
      {hidden > 0 && (
        <span className="avatar-count" title={t('taskActions.moreAssignees', { n: hidden })}>
          +{hidden}
        </span>
      )}
    </div>
  );
}

export default AvatarStack;
