import { useCallback, useEffect, useState } from 'react';
import { toast } from 'react-toastify';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../context/AuthContext';
import { authService } from '../../services/authService';
import { getSessionId } from '../../services/session';
import type { ActiveDevice } from '../../types/api';

export function useDevices() {
  const { t } = useTranslation();
  const { logout } = useAuth();
  const [sessions, setSessions] = useState<ActiveDevice[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [endingId, setEndingId] = useState<number | null>(null);

  const currentSessionId = getSessionId();
  const isCurrentSession = (session: ActiveDevice) => String(session.id) === String(currentSessionId);

  const load = useCallback(async () => {
    setIsLoading(true);
    try {
      setSessions(await authService.listDevices());
    } catch (error) {
      console.error('Error:', error);
      toast.error(t('devices.messages.loadError'));
    } finally {
      setIsLoading(false);
    }
  }, [t]);

  useEffect(() => {
    load();
  }, [load]);

  const endSession = async (session: ActiveDevice) => {
    const isCurrent = isCurrentSession(session);
    if (!window.confirm(isCurrent ? t('devices.messages.endCurrentConfirm') : t('devices.messages.endConfirm'))) {
      return;
    }

    setEndingId(session.id);
    try {
      await authService.endDevice(session.id);
      if (isCurrent) {
        await logout();
        window.location.href = '/';
        return;
      }
      toast.success(t('devices.messages.ended'));
      await load();
    } catch (error) {
      console.error('Error:', error);
      toast.error(t('devices.messages.endError'));
    } finally {
      setEndingId(null);
    }
  };

  return { sessions, isLoading, endingId, isCurrentSession, endSession };
}
