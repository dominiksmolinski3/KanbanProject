import { useTranslation } from 'react-i18next';
import type { BoardInvitation, Id } from '../../types/api';
import Icon from '../Icon';

interface PendingInvitationsProps {
  invitations: BoardInvitation[];
  onRevoke: (invitationId: Id) => void;
}

export default function PendingInvitations({ invitations, onRevoke }: PendingInvitationsProps) {
  const { t } = useTranslation();

  return (
    <div className="board-pending">
      <h3 className="board-invite-heading">{t('boards.invitations.pendingHeading')}</h3>
      <ul className="board-invitation-list">
        {invitations.map(invitation => (
          <li key={invitation.id} className="board-invitation pending">
            <span className="board-member-avatar board-pending-icon" aria-hidden="true">
              <Icon name="mail" size="sm" />
            </span>
            <span className="board-member-email" dir="ltr">{invitation.email}</span>
            <span className="board-role">
              {invitation.role === 'VIEWER' ? t('boards.members.viewer') : t('boards.members.member')}
              {' · '}
              {t('boards.invitations.pending')}
            </span>
            <button
              type="button"
              className="board-member-remove"
              title={t('boards.invitations.revoke')}
              aria-label={t('boards.invitations.revoke')}
              onClick={() => onRevoke(invitation.id)}
            >
              <Icon name="close" size="sm" />
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}
