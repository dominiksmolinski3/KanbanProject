import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import Icon from '../Icon';
import { pushApi } from '../../services/pushApi';
import {
  disablePush,
  enablePush,
  pushEnabled,
  pushSupport,
  PushBlockedError,
  PushServiceError,
} from './push';

const ENABLED = ['push', 'enabled'] as const;
const CONFIG = ['push', 'config'] as const;
const PROBLEMS = {
  blocked: 'devices.messaging.push.blocked',
  unreachable: 'devices.messaging.push.unreachable',
  failed: 'devices.messaging.push.failed',
} as const;

export default function MessagingPush({ deviceId }: { deviceId: number }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const support = pushSupport();
  const config = useQuery({ queryKey: CONFIG, queryFn: pushApi.config, enabled: support !== 'none' });
  const enabled = useQuery({ queryKey: ENABLED, queryFn: pushEnabled, enabled: support !== 'none' });
  const [problem, setProblem] = useState<keyof typeof PROBLEMS | null>(null);
  const [pending, setPending] = useState(false);

  const offered = support === 'native' ? config.data?.fcm : Boolean(config.data?.webPushKey);
  if (support === 'none') {
    return <p className="messaging-caption">{t('devices.messaging.push.unsupported')}</p>;
  }
  if (!config.data || !offered) return null;

  const change = async (turnOn: boolean) => {
    setPending(true);
    setProblem(null);
    try {
      if (turnOn) {
        await enablePush(deviceId, config.data, {
          title: t('devices.messaging.push.noticeTitle'),
          body: t('devices.messaging.push.noticeBody'),
        });
      } else {
        await disablePush();
      }
      await queryClient.invalidateQueries({ queryKey: ENABLED });
    } catch (failure) {
      console.warn('[Push] Could not change notifications:', failure);
      setProblem(failure instanceof PushBlockedError ? 'blocked' : failure instanceof PushServiceError ? 'unreachable' : 'failed');
    } finally {
      setPending(false);
    }
  };

  return (
    <div className="messaging-push">
      <h3 className="messaging-subtitle">{t('devices.messaging.push.title')}</h3>
      <p className="messaging-caption">{t('devices.messaging.push.body')}</p>
      {enabled.data && (
        <p className="messaging-ready">
          <Icon name="check" size="sm" />
          {t('devices.messaging.push.on')}
        </p>
      )}
      {problem && (
        <p className="messaging-error" role="alert">
          {t(PROBLEMS[problem])}
        </p>
      )}
      <div className="messaging-actions">
        <button type="button" className="btn btn-secondary btn-sm" disabled={pending}
          onClick={() => void change(!enabled.data)}>
          {t(enabled.data ? 'devices.messaging.push.disable' : 'devices.messaging.push.enable')}
        </button>
      </div>
    </div>
  );
}
