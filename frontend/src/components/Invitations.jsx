import React from 'react';
import { useTranslation } from 'react-i18next';
import { useKanban } from '../context/KanbanContext';
import '../styles/components/BoardMembers.css';

/**
 * The boards somebody has been asked to join, and their answer.
 *
 * <p>The invitee's half of the membership model. Before invitations existed an owner typed an
 * address and the account was simply on the board; this is the screen that makes that a decision
 * rather than something that happened to you.
 *
 * <p>It renders nothing when there is nothing outstanding, deliberately: an empty panel headed
 * "invitations" on every visit is noise, and the badge on the board switcher is what says there is
 * something here to look at.
 */
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
            {/* The inviter's name is the only thing said about them, and it is what makes the
                offer legible - "a board" from nobody in particular is not something anybody can
                sensibly accept. */}
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
