import { toast } from 'react-toastify';
import { useTranslation } from 'react-i18next';
import Icon from '../Icon';
import { recoveryKeyGroups } from '../../crypto/recoveryKey';

interface RecoveryKeyFieldsProps {
  recoveryKey: string;
  saved: boolean;
  check: string;
  onSaved: (saved: boolean) => void;
  onCheck: (check: string) => void;
}

export default function RecoveryKeyFields({ recoveryKey, saved, check, onSaved, onCheck }: RecoveryKeyFieldsProps) {
  const { t } = useTranslation();
  const groups = recoveryKeyGroups(recoveryKey);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(groups.join(' '));
      toast.success(t('devices.messaging.setup.copied'));
    } catch {
      toast.error(t('devices.messaging.setup.copyFailed'));
    }
  };

  const download = () => {
    const file = new Blob([`${t('devices.messaging.setup.fileHeading')}\n\n${groups.join(' ')}\n`], { type: 'text/plain' });
    const link = document.createElement('a');
    link.href = URL.createObjectURL(file);
    link.download = 'kanban-recovery-key.txt';
    link.click();
    URL.revokeObjectURL(link.href);
  };

  return (
    <>
      <p className="messaging-muted">{t('devices.messaging.setup.keyBody')}</p>
      <div className="messaging-recovery" dir="ltr" aria-label={t('devices.messaging.setup.keyLabel')} role="group">
        {groups.map((group, index) => <span key={index}>{group}</span>)}
      </div>
      <div className="messaging-actions">
        <button type="button" className="btn btn-secondary btn-sm" onClick={download}>
          <Icon name="download" size="sm" />
          {t('devices.messaging.setup.download')}
        </button>
        <button type="button" className="btn btn-secondary btn-sm" onClick={copy}>
          <Icon name="file" size="sm" />
          {t('devices.messaging.setup.copy')}
        </button>
      </div>
      <p className="messaging-caption">{t('devices.messaging.setup.keepIt')}</p>
      <label className="messaging-check">
        <input type="checkbox" checked={saved} onChange={(event) => onSaved(event.target.checked)} />
        <span>{t('devices.messaging.setup.saved')}</span>
      </label>
      <label className="field-label" htmlFor="messaging-key-check">{t('devices.messaging.setup.checkLabel')}</label>
      <input
        id="messaging-key-check"
        className="field-input messaging-key-input"
        dir="ltr"
        autoComplete="off"
        spellCheck={false}
        maxLength={4}
        value={check}
        onChange={(event) => onCheck(event.target.value)}
      />
    </>
  );
}
