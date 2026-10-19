import type { CSSProperties } from 'react';
import { useTranslation } from 'react-i18next';
import { hueOf, initialsOf } from '../../board/cardModel';
import type { Board, BoardMember, Id } from '../../types/api';
import Icon from '../Icon';

interface MemberListProps {
  board: Board;
  currentUserId: Id | undefined;
  owned: boolean;
  onRemove: (memberId: Id) => void;
}

export default function MemberList({ board, currentUserId, owned, onRemove }: MemberListProps) {
  const { t } = useTranslation();

  const roleOf = (member: BoardMember) => {
    if (member.id === board.ownerId) return t('boards.members.owner');
    return member.role === 'VIEWER' ? t('boards.members.viewer') : t('boards.members.member');
  };

  return (
    <ul className="board-member-list">
      {board.members.map(member => {
        const isOwner = member.id === board.ownerId;
        const isMe = member.id === currentUserId;
        const canRemove = !isOwner && (owned || isMe);
        const removeLabel = isMe ? t('boards.members.leave') : t('boards.members.remove');
        return (
          <li key={member.id} className="board-member">
            <span className="board-member-avatar" aria-hidden="true" style={{ '--avatar-hue': hueOf(member.id) } as CSSProperties}>
              {initialsOf(member.name || member.email)}
            </span>
            <span className="board-member-identity">
              <span className="board-member-name">
                {member.name}
                {isMe && <span className="board-member-you">{t('boards.members.you')}</span>}
              </span>
              <span className="board-member-email" dir="ltr">{member.email}</span>
            </span>
            <span className={`board-role${isOwner ? ' owner' : ''}`}>{roleOf(member)}</span>
            {canRemove ? (
              <button
                type="button"
                className="board-member-remove"
                title={removeLabel}
                aria-label={removeLabel}
                onClick={() => onRemove(member.id)}
              >
                <Icon name={isMe ? 'logout' : 'close'} size="sm" />
              </button>
            ) : (
              <span className="board-member-remove-spacer" aria-hidden="true" />
            )}
          </li>
        );
      })}
    </ul>
  );
}
