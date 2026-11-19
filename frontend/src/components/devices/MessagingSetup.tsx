import { useMemo, useState, type FormEvent } from 'react';
import { toast } from 'react-toastify';
import { useTranslation } from 'react-i18next';
import Modal, { ModalTitle } from '../ui/Modal';
import Icon from '../Icon';
import { deviceVault } from '../../crypto/client';
import { generateRecoveryKey, recoveryKeyGroups } from '../../crypto/recoveryKey';
import { cryptoApi } from '../../services/cryptoApi';
import { useAsyncAction } from '../../hooks/useAsyncAction';
import '../../styles/components/Forms.css';

interface MessagingSetupProps {
  mode: 'create' | 'replace';
  onRegister: () => Promise<void>;
  onClose: () => void;
}

export default function MessagingSetup({ mode, onRegister, onClose }: MessagingSetupProps) {
  const { t } = useTranslation();
  const [step, setStep] = useState<'intro' | 'key'>('intro');
  const [saved, setSaved] = useState(false);
  const [check, setCheck] = useState('');
  const recoveryKey = useMemo(() => generateRecoveryKey(), []);
  const groups = useMemo(() => recoveryKeyGroups(recoveryKey), [recoveryKey]);
  const { pending, error, run } = useAsyncAction();

  const lastGroup = groups[groups.length - 1];
  const confirmed = saved && check.trim().toUpperCase() === lastGroup;

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

  const finish = async (event: FormEvent) => {
    event.preventDefault();
    if (!confirmed) return;
    const done = await run(async () => {
      const identity = await deviceVault().createIdentity(recoveryKey);
      if (mode === 'create') {
        await cryptoApi.createIdentity(identity);
      } else {
        await cryptoApi.replaceIdentity(identity);
      }
      await onRegister();
    }, t('devices.messaging.errors.generic'));
    if (done) {
      toast.success(t('devices.messaging.setup.done'));
      onClose();
    }
  };

  return (
    <Modal onClose={pending ? () => undefined : onClose} overlayClassName="modal-overlay" contentClassName="form-modal messaging-setup" busy={pending}>
      {step === 'intro' ? (
        <div className="messaging-setup-step">
          <span className="messaging-setup-icon" aria-hidden="true"><Icon name="lock" /></span>
          <ModalTitle className="messaging-setup-title">
            {mode === 'create' ? t('devices.messaging.setup.introTitle') : t('devices.messaging.reset.title')}
          </ModalTitle>
          {mode === 'create' ? (
            <>
              <p className="messaging-muted">{t('devices.messaging.setup.introBody')}</p>
              <ul className="messaging-points">
                <li>{t('devices.messaging.setup.pointBrowser')}</li>
                <li>{t('devices.messaging.setup.pointRecovery')}</li>
                <li>{t('devices.messaging.setup.pointEverywhere')}</li>
              </ul>
            </>
          ) : (
            <p className="messaging-muted">{t('devices.messaging.reset.body')}</p>
          )}
          <div className="messaging-actions">
            <button
              type="button"
              className={`btn ${mode === 'create' ? 'btn-primary' : 'btn-danger'}`}
              onClick={() => setStep('key')}
            >
              {mode === 'create' ? t('devices.messaging.buttons.setUp') : t('devices.messaging.buttons.startOver')}
            </button>
            <button type="button" className="btn btn-secondary" onClick={onClose}>
              {t('devices.messaging.buttons.cancel')}
            </button>
          </div>
        </div>
      ) : (
        <form className="messaging-setup-step" onSubmit={finish}>
          <ModalTitle className="messaging-setup-title">{t('devices.messaging.setup.keyTitle')}</ModalTitle>
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
            <input type="checkbox" checked={saved} onChange={(event) => setSaved(event.target.checked)} />
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
            onChange={(event) => setCheck(event.target.value)}
          />
          {error && <p className="messaging-error" role="alert">{error}</p>}
          <button type="submit" className="btn btn-primary" disabled={!confirmed || pending}>
            {pending ? t('devices.messaging.setup.finishing') : t('devices.messaging.setup.finish')}
          </button>
          <p className="messaging-caption">{t('devices.messaging.setup.cannotSkip')}</p>
        </form>
      )}
    </Modal>
  );
}
