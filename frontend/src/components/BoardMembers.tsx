import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../context/AuthContext';
import { useKanban } from '../context/KanbanContext';
import type { BoardRole, Id } from '../types/api';
import Icon from './Icon';
import BoardHeader from './boardMembers/BoardHeader';
import InviteForm from './boardMembers/InviteForm';
import MemberList from './boardMembers/MemberList';
import PendingInvitations from './boardMembers/PendingInvitations';
import { usePendingInvitations } from './boardMembers/usePendingInvitations';
import '../styles/components/BoardMembers.css';

function BoardMembers() {
  const { activeBoard, renameBoard, deleteBoard, inviteToBoard, revokeInvitation, removeBoardMember } = useKanban();
  const { user } = useAuth();
  const { t } = useTranslation();
  const [busy, setBusy] = useState(false);

  const owned = Boolean(activeBoard?.owned);
  const { invitations, reload } = usePendingInvitations(activeBoard?.id, owned);

  if (!activeBoard) {
    return null;
  }

  const invite = async (address: string, role: BoardRole) => {
    setBusy(true);
    const invitation = await inviteToBoard(activeBoard.id, address, role);
    if (invitation) {
      await reload();
    }
    setBusy(false);
    return Boolean(invitation);
  };

  const revoke = async (invitationId: Id) => {
    if (await revokeInvitation(activeBoard.id, invitationId)) {
      await reload();
    }
  };

  const rename = async (name: string) => {
    setBusy(true);
    const renamed = await renameBoard(activeBoard.id, name);
    setBusy(false);
    return renamed;
  };

  const remove = async (memberId: Id) => {
    const leaving = memberId === user?.id;
    const question = leaving ? t('boards.members.leaveConfirm') : t('boards.members.removeConfirm');
    if (!window.confirm(question)) {
      return;
    }
    await removeBoardMember(activeBoard.id, memberId);
  };

  const destroy = async () => {
    if (!window.confirm(t('boards.members.deleteConfirm', { name: activeBoard.name }))) {
      return;
    }
    await deleteBoard(activeBoard.id);
  };

  return (
    <section className="page-panel board-panel" aria-labelledby="board-panel-heading">
      <BoardHeader board={activeBoard} owned={owned} busy={busy} onRename={rename} />

      <p className="board-panel-explainer">{t('boards.members.explainer')}</p>

      <MemberList board={activeBoard} currentUserId={user?.id} owned={owned} onRemove={remove} />

      {owned && invitations.length > 0 && (
        <PendingInvitations invitations={invitations} onRevoke={revoke} />
      )}

      {owned && <InviteForm busy={busy} onInvite={invite} />}

      {owned && (
        <div className="board-danger">
          <button type="button" className="btn btn-danger-quiet btn-sm board-delete" onClick={destroy}>
            <Icon name="trash" size="sm" />
            {t('boards.members.delete')}
          </button>
        </div>
      )}
    </section>
  );
}

export default BoardMembers;
