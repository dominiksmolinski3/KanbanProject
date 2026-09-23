import React from 'react';
import { useTranslation } from 'react-i18next';
import { useKanban } from '../context/KanbanContext';
import '../styles/components/BoardMembers.css';

function Invitations() {
  const { myInvitations, acceptInvitation, declineInvitation } = useKanban();
  const { t } = useTranslation();

  if (!myInvitations || myInvitations.length === 0) {
    return null;
  }

  return (
    <section className="board-panel" data-testid="invitations-panel">
      <header className="board-panel-header">
        <h2>{t('boards.invitations.heading')}</h2>
      </header>

      <p className="board-panel-explainer">{t('boards.invitations.explainer')}</p>

      <ul className="board-invitation-list">
        {myInvitations.map(invitation => (
          <li key={invitation.id} className="board-invitation">
            <span className="board-member-name">{invitation.boardName}</span>
            <span className="board-member-email">
              {t('boards.invitations.from', { name: invitation.invitedByName })}
            </span>
            <button
              type="button"
              className="board-invitation-accept"
              onClick={() => acceptInvitation(invitation.id)}
            >
              {t('boards.invitations.accept')}
            </button>
            <button
              type="button"
              className="board-invitation-decline"
              onClick={() => declineInvitation(invitation.id)}
            >
              {t('boards.invitations.decline')}
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}

export default Invitations;
