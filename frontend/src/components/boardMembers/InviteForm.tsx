import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import type { BoardRole } from '../../types/api';
import Icon from '../Icon';

interface InviteFormProps {
  busy: boolean;
  onInvite: (email: string, role: BoardRole) => Promise<boolean>;
}

export default function InviteForm({ busy, onInvite }: InviteFormProps) {
  const { t } = useTranslation();
  const [email, setEmail] = useState('');
  const [role, setRole] = useState<BoardRole>('MEMBER');

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const address = email.trim();
    if (!address || busy) {
      return;
    }
    if (await onInvite(address, role)) {
      setEmail('');
      setRole('MEMBER');
    }
  };

  return (
    <form className="board-invite" onSubmit={submit}>
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
          value={role}
          onChange={(event) => setRole(event.target.value as BoardRole)}
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
  );
}
