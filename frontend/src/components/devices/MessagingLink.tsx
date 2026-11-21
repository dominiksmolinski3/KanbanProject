import { useEffect, useRef, useState } from 'react';
import { toast } from 'react-toastify';
import { useTranslation } from 'react-i18next';
import Modal, { ModalTitle } from '../ui/Modal';
import { cryptoApi } from '../../services/cryptoApi';
import QrCode from './QrCode';
import { receiveLink, registerThisBrowser, startLink, type StartedLink } from './messagingFlows';
import '../../styles/components/Forms.css';

type Register = (register: (userId: number, sessionId: number) => Promise<unknown>) => Promise<void>;

interface MessagingLinkProps {
  onRegister: Register;
  onClose: () => void;
  pollMs?: number;
}

const POLL_MS = 2000;

export default function MessagingLink({ onRegister, onClose, pollMs = POLL_MS }: MessagingLinkProps) {
  const { t } = useTranslation();
  const [link, setLink] = useState<StartedLink | null>(null);
  const [failed, setFailed] = useState<'start' | 'expired' | 'finish' | null>(null);
  const [copied, setCopied] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const active = useRef<string | null>(null);

  useEffect(() => {
    let current = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    setLink(null);
    setFailed(null);
    startLink().then((started) => {
      if (!current) {
        void cryptoApi.cancelLink(started.id).catch(() => undefined);
        return;
      }
      active.current = started.id;
      setLink(started);
      const poll = async () => {
        if (!current) return;
        if (Date.parse(started.expiresAt) <= Date.now()) {
          setFailed('expired');
          return;
        }
        try {
          if (await receiveLink(started.id)) {
            await onRegister(registerThisBrowser);
            if (current) {
              toast.success(t('devices.messaging.setup.done'));
              onClose();
            }
            return;
          }
        } catch {
          if (current) setFailed('finish');
          return;
        }
        timer = setTimeout(poll, pollMs);
      };
      timer = setTimeout(poll, pollMs);
    }, () => current && setFailed('start'));
    return () => {
      current = false;
      clearTimeout(timer);
      if (active.current) void cryptoApi.cancelLink(active.current).catch(() => undefined);
      active.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- a new attempt restarts the whole exchange
  }, [attempt]);

  const copy = async () => {
    if (!link) return;
    try {
      await navigator.clipboard.writeText(link.code);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  };

  const address = link ? `${window.location.origin}/link#${link.code}` : '';

  return (
    <Modal onClose={onClose} overlayClassName="modal-overlay" contentClassName="form-modal messaging-setup">
      <div className="form-container messaging-setup-step">
        <ModalTitle className="messaging-setup-title">{t('devices.messaging.link.title')}</ModalTitle>
        <p className="messaging-muted">{t('devices.messaging.link.body')}</p>
        {link && !failed && (
          <>
            <div className="messaging-qr">
              <QrCode value={address} label={t('devices.messaging.link.qr')} />
            </div>
            <p className="messaging-caption">{t('devices.messaging.link.orCode')}</p>
            <code className="messaging-link-code" dir="ltr" data-testid="link-code">{link.code}</code>
            <div className="messaging-actions">
              <button type="button" className="btn btn-secondary btn-sm" onClick={() => void copy()}>
                {t(copied ? 'devices.messaging.link.copied' : 'devices.messaging.link.copy')}
              </button>
            </div>
            <p className="messaging-caption" role="status">{t('devices.messaging.link.waiting')}</p>
          </>
        )}
        {!link && !failed && <p className="messaging-muted" role="status">{t('app.loading')}</p>}
        {failed && (
          <p className="messaging-error" role="alert">{t(`devices.messaging.link.failed.${failed}`)}</p>
        )}
        <div className="messaging-actions">
          {failed && (
            <button type="button" className="btn btn-primary" onClick={() => setAttempt((count) => count + 1)}>
              {t('devices.messaging.link.again')}
            </button>
          )}
          <button type="button" className="btn btn-secondary" onClick={onClose}>
            {t('devices.messaging.buttons.cancel')}
          </button>
        </div>
      </div>
    </Modal>
  );
}
