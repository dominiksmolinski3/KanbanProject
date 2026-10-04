import React from 'react';
import { useTranslation } from 'react-i18next';
import Icon from './Icon';
import { useKanban } from '../context/KanbanContext';
import '../styles/components/BoardMembers.css';

function Invitations() {
  const { myInvitations, acceptInvitation, declineInvitation } = useKanban();
  const { t } = useTranslation();

  if (!myInvitations || myInvitations.length === 0) {
    return null;
  }

  return (
    <section className="page-panel board-panel invitations-panel" data-testid="invitations-panel" aria-labelledby="invitations-heading">
      <header className="board-panel-header">
        <h2 className="page-panel-title" id="invitations-heading">{t('boards.invitations.heading')}</h2>
      </header>

      <p className="board-panel-explainer">{t('boards.invitations.explainer')}</p>

      <ul className="board-invitation-list">
        {myInvitations.map(invitation => (
          <li key={invitation.id} className="board-invitation incoming">
            <span className="board-member-avatar board-pending-icon" aria-hidden="true">
              <Icon name="board" size="sm" />
            </span>
            <span className="board-member-identity">
              <span className="board-member-name">{invitation.boardName}</span>
              <span className="board-member-email">
                {t('boards.invitations.from', { name: invitation.invitedByName })}
              </span>
            </span>
            <span className="board-invitation-actions">
              <button
                type="button"
                className="btn btn-secondary btn-sm board-invitation-decline"
                onClick={() => declineInvitation(invitation.id)}
              >
                {t('boards.invitations.decline')}
              </button>
              <button
                type="button"
                className="btn btn-primary btn-sm board-invitation-accept"
                onClick={() => acceptInvitation(invitation.id)}
              >
                {t('boards.invitations.accept')}
              </button>
            </span>
          </li>
        ))}
      </ul>
    </section>
  );
}

export default Invitations;
