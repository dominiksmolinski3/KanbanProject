import React, { useCallback, useEffect, useState } from 'react';
import { NAME_MAX_LENGTH } from '../services/textLimits';
import { useTranslation } from 'react-i18next';
import Icon from './Icon';
import { hueOf, initialsOf } from '../board/cardModel';
import { useAuth } from '../context/AuthContext';
import { useKanban } from '../context/KanbanContext';
import { fetchBoardInvitations } from '../services/boardApi';
import '../styles/components/BoardMembers.css';

function BoardMembers() {
  const { activeBoard, renameBoard, deleteBoard, inviteToBoard, revokeInvitation, removeBoardMember } = useKanban();
  const { user } = useAuth();
  const { t } = useTranslation();

  const [email, setEmail] = useState('');
  const [inviteRole, setInviteRole] = useState('MEMBER');
  const [invitations, setInvitations] = useState([]);
  const [renaming, setRenaming] = useState(false);
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);

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
    const invitation = await inviteToBoard(activeBoard.id, address, inviteRole);
    if (invitation) {
      setEmail('');
      setInviteRole('MEMBER');
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

  const roleOf = (member) => {
    if (member.id === activeBoard.ownerId) return t('boards.members.owner');
    return member.role === 'VIEWER' ? t('boards.members.viewer') : t('boards.members.member');
  };

  return (
    <section className="page-panel board-panel" aria-labelledby="board-panel-heading">
      {renaming ? (
        <form className="board-rename" onSubmit={handleRename}>
          <label className="visually-hidden" htmlFor="board-rename-input">{t('boards.members.rename')}</label>
          <input
            id="board-rename-input"
            className="field-input"
            type="text"
            value={name}
            autoFocus
            maxLength={NAME_MAX_LENGTH}
            onChange={(event) => setName(event.target.value)}
          />
          <button type="submit" className="btn btn-primary btn-sm">{t('boards.members.save')}</button>
          <button type="button" className="btn btn-secondary btn-sm" onClick={() => setRenaming(false)}>
            {t('boards.members.cancel')}
          </button>
        </form>
      ) : (
        <header className="board-panel-header">
          <h2 className="page-panel-title" id="board-panel-heading">{activeBoard.name}</h2>
          <span className={`board-role${owned ? ' owner' : ''}`}>
            {owned ? t('boards.members.youOwnIt') : t('boards.members.sharedWithYou')}
          </span>
          {owned && (
            <button
              type="button"
              className="btn btn-ghost btn-sm board-panel-action"
              onClick={() => {
                setName(activeBoard.name);
                setRenaming(true);
              }}
            >
              <Icon name="edit" size="sm" />
              {t('boards.members.rename')}
            </button>
          )}
        </header>
      )}

      <p className="board-panel-explainer">{t('boards.members.explainer')}</p>

      <ul className="board-member-list">
        {activeBoard.members.map(member => {
          const isOwner = member.id === activeBoard.ownerId;
          const isMe = member.id === user?.id;
          const canRemove = !isOwner && (owned || isMe);
          const removeLabel = isMe ? t('boards.members.leave') : t('boards.members.remove');
          return (
            <li key={member.id} className="board-member">
              <span className="board-member-avatar" aria-hidden="true" style={{ '--avatar-hue': hueOf(member.id) }}>
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
                  onClick={() => handleRemove(member.id)}
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

      {owned && invitations.length > 0 && (
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
                  onClick={() => handleRevoke(invitation.id)}
                >
                  <Icon name="close" size="sm" />
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}

      {owned && (
        <form className="board-invite" onSubmit={handleInvite}>
          <label className="field-label" htmlFor="board-invite-email">{t('boards.invitations.inviteLabel')}</label>
          <div className="board-invite-row">
            <input
              id="board-invite-email"
              className="field-input"
              type="email"
              dir="ltr"
              value={email}
              maxLength={255}
              placeholder={t('boards.invitations.invitePlaceholder')}
              onChange={(event) => setEmail(event.target.value)}
            />
            <label htmlFor="board-invite-role" className="visually-hidden">
              {t('boards.invitations.roleLabel')}
            </label>
            <select
              id="board-invite-role"
              className="field-input"
              value={inviteRole}
              onChange={(event) => setInviteRole(event.target.value)}
              aria-label={t('boards.invitations.roleLabel')}
            >
              <option value="MEMBER">{t('boards.invitations.roleMember')}</option>
              <option value="VIEWER">{t('boards.invitations.roleViewer')}</option>
            </select>
            <button type="submit" className="btn btn-primary" disabled={busy}>
              <Icon name="send" size="sm" />
              {t('boards.invitations.invite')}
            </button>
          </div>
          <p className="board-invite-note">{t('boards.invitations.inviteNote')}</p>
        </form>
      )}

      {owned && (
        <div className="board-danger">
          <button type="button" className="btn btn-danger-quiet btn-sm board-delete" onClick={handleDelete}>
            <Icon name="trash" size="sm" />
            {t('boards.members.delete')}
          </button>
        </div>
      )}
    </section>
  );
}

export default BoardMembers;
