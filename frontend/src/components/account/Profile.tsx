import { useState, type ChangeEvent, type CSSProperties } from 'react';
import { toast } from 'react-toastify';
import { useTranslation } from 'react-i18next';
import { getUserAvatar, uploadUserAvatar } from '../../services/api';
import useUserAvatar, { clearAvatarCache } from '../../board/useUserAvatar';
import { hueOf, initialsOf } from '../../board/cardModel';
import { useAsyncAction } from '../../hooks/useAsyncAction';
import type { User } from '../../types/api';

const AVATAR_MAX_BYTES = 1024 * 1024;
const AVATAR_TYPES = ['image/jpeg', 'image/png', 'image/webp', 'image/gif'];

export default function Profile({ user }: { user: User }) {
  const { t } = useTranslation();
  const stored = useUserAvatar(user.id);
  const [preview, setPreview] = useState<string | null>(null);
  const { pending: busy, run } = useAsyncAction({
    onError: (error) => toast.error((error as Error).message || t('account.errors.generic')),
  });
  const url = preview || stored;

  const upload = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;
    if (!AVATAR_TYPES.includes(file.type)) {
      toast.info(t('usersManagement.messages.fileTypeError'));
      return;
    }
    if (file.size > AVATAR_MAX_BYTES) {
      toast.info(t('usersManagement.messages.fileTooLarge'));
      return;
    }
    await run(async () => {
      await uploadUserAvatar(user.id, file);
      clearAvatarCache();
      setPreview(await getUserAvatar(user.id));
      toast.success(t('usersManagement.messages.avatarUpdated'));
    });
  };

  return (
    <section className="page-panel account-profile">
      {url ? (
        <img className="account-avatar" src={url} alt="" />
      ) : (
        <span className="account-avatar" aria-hidden="true" style={{ '--avatar-hue': hueOf(user.id) } as CSSProperties}>
          {initialsOf(user.name || user.email)}
        </span>
      )}
      <div className="account-identity">
        <strong>{user.name || user.email}</strong>
        <span>{user.email}</span>
      </div>
      <label className={`btn btn-secondary btn-sm account-avatar-change${busy ? ' busy' : ''}`}>
        {t('account.avatar.change')}
        <input
          type="file"
          accept={AVATAR_TYPES.join(',')}
          disabled={busy}
          onChange={upload}
        />
      </label>
    </section>
  );
}
