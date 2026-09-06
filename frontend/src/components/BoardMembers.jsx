import React, { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../context/AuthContext';
import { useKanban } from '../context/KanbanContext';
import { fetchBoardInvitations } from '../services/boardApi';
import '../styles/components/BoardMembers.css';

/**
 * The board, and who is on it.
 *
 * <p>This is the screen the tenancy model needed to become usable. Access is decided by membership
 * now, so there has to be somewhere a person can see the list and change it — otherwise every
 * account is a board of one, and the assignees, the WIP limits and the chat have nobody to talk to.
 *
 * <p>Only the owner can rename the board, delete it, or change who is on it. Everyone else sees the
 * same list and a way out.
 */
function BoardMembers() {
  const { activeBoard, renameBoard, deleteBoard, inviteToBoard, revokeInvitation, removeBoardMember } = useKanban();
  const { user } = useAuth();
  const { t } = useTranslation();

  const [email, setEmail] = useState('');
  const [invitations, setInvitations] = useState([]);
  const [renaming, setRenaming] = useState(false);
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);

  /*
   * Kept here rather than in KanbanContext. A board's outstanding invitations are only ever read
   * on this panel, by the one person allowed to see them, and putting them in the shared context
   * would mean every screen carrying a list nothing else asks about.
   */
  const boardId = activeBoard?.id;
  const owned = Boolean(activeBoard?.owned);

  const loadInvitations = useCallback(async () => {
    if (!boardId || !owned) {
      setInvitations([]);
      return;
    }
    try {
      setInvitations(await fetchBoardInvitations(boardId));
    } catch (err) {
      console.error('Error fetching board invitations:', err);
      setInvitations([]);
    }
  }, [boardId, owned]);

  useEffect(() => {
    loadInvitations();
  }, [loadInvitations]);

  if (!activeBoard) {
    return null;
  }

  const handleInvite = async (event) => {
    event.preventDefault();
    const address = email.trim();
    if (!address || busy) {
      return;
    }
    setBusy(true);
    const invitation = await inviteToBoard(activeBoard.id, address);
    if (invitation) {
      setEmail('');
      await loadInvitations();
    }
    setBusy(false);
  };

  const handleRevoke = async (invitationId) => {
    if (await revokeInvitation(activeBoard.id, invitationId)) {
      await loadInvitations();
    }
  };

  const handleRename = async (event) => {
    event.preventDefault();
    const trimmed = name.trim();
    if (!trimmed || busy) {
      return;
    }
    setBusy(true);
    const renamed = await renameBoard(activeBoard.id, trimmed);
    setBusy(false);
    if (renamed) {
      setRenaming(false);
    }
  };

  const handleRemove = async (memberId) => {
    const leaving = memberId === user?.id;
    const question = leaving ? t('boards.members.leaveConfirm') : t('boards.members.removeConfirm');
    if (!window.confirm(question)) {
      return;
    }
    await removeBoardMember(activeBoard.id, memberId);
  };

  const handleDelete = async () => {
    if (!window.confirm(t('boards.members.deleteConfirm', { name: activeBoard.name }))) {
      return;
    }
    await deleteBoard(activeBoard.id);
  };

  return (
    <section className="board-panel">
      <header className="board-panel-header">
        {renaming ? (
          <form className="board-rename" onSubmit={handleRename}>
            <input
              type="text"
              value={name}
              autoFocus
              maxLength={255}
              onChange={(event) => setName(event.target.value)}
            />
            <button type="submit">{t('boards.members.save')}</button>
            <button type="button" onClick={() => setRenaming(false)}>
              {t('boards.members.cancel')}
            </button>
          </form>
        ) : (
          <>
            <h2>{activeBoard.name}</h2>
            {owned ? (
              <span className="board-role owner">{t('boards.members.youOwnIt')}</span>
            ) : (
              <span className="board-role">{t('boards.members.sharedWithYou')}</span>
            )}
            {owned && (
              <button
                type="button"
                className="board-panel-action"
                onClick={() => {
                  setName(activeBoard.name);
                  setRenaming(true);
                }}
              >
                {t('boards.members.rename')}
              </button>
            )}
          </>
        )}
      </header>

      <p className="board-panel-explainer">{t('boards.members.explainer')}</p>

      <ul className="board-member-list">
        {activeBoard.members.map(member => {
          const isOwner = member.id === activeBoard.ownerId;
          const isMe = member.id === user?.id;
          return (
            <li key={member.id} className="board-member">
              <span className="board-member-name">
                {member.name}
                {isMe && <span className="board-member-you">{t('boards.members.you')}</span>}
              </span>
              <span className="board-member-email">{member.email}</span>
              {isOwner ? (
                <span className="board-role owner">{t('boards.members.owner')}</span>
              ) : (
                <span className="board-role">{t('boards.members.member')}</span>
              )}
              {/* The owner cannot be removed by anyone, including themselves: nothing can
                  appoint a replacement, so the board would be left unmanageable. */}
              {!isOwner && (owned || isMe) && (
                <button
                  type="button"
                  className="board-member-remove"
                  title={isMe ? t('boards.members.leave') : t('boards.members.remove')}
                  onClick={() => handleRemove(member.id)}
                >
                  ×
                </button>
              )}
            </li>
          );
        })}
      </ul>

      {owned && invitations.length > 0 && (
        <>
          <h3 className="board-invite-heading">{t('boards.invitations.pendingHeading')}</h3>
          <ul className="board-invitation-list">
            {invitations.map(invitation => (
              <li key={invitation.id} className="board-invitation">
                <span className="board-member-email">{invitation.email}</span>
                <span className="board-role">{t('boards.invitations.pending')}</span>
                <button
                  type="button"
                  className="board-member-remove"
                  title={t('boards.invitations.revoke')}
                  onClick={() => handleRevoke(invitation.id)}
                >
                  ×
                </button>
              </li>
            ))}
          </ul>
        </>
      )}

      {owned && (
        <form className="board-invite" onSubmit={handleInvite}>
          <label htmlFor="board-invite-email">{t('boards.invitations.inviteLabel')}</label>
          <div className="board-invite-row">
            <input
              id="board-invite-email"
              type="email"
              value={email}
              maxLength={255}
              placeholder={t('boards.invitations.invitePlaceholder')}
              onChange={(event) => setEmail(event.target.value)}
            />
            <button type="submit" disabled={busy}>{t('boards.invitations.invite')}</button>
          </div>
          {/* Not a hedge. Nobody is added by this form: it sends an invitation the other person
              has to accept, and the server answers identically whether or not that address has an
              account here, so it cannot be used to find out which addresses do. */}
          <p className="board-invite-note">{t('boards.invitations.inviteNote')}</p>
        </form>
      )}

      {owned && (
        <button type="button" className="board-delete" onClick={handleDelete}>
          {t('boards.members.delete')}
        </button>
      )}
    </section>
  );
}

export default BoardMembers;
