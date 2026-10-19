import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'react-toastify';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../context/AuthContext';
import { authService } from '../../services/authService';
import { getSessionId } from '../../services/session';
import type { ActiveDevice } from '../../types/api';

const DEVICES = ['devices'] as const;
const NONE: ActiveDevice[] = [];

export function useDevices() {
  const { t } = useTranslation();
  const { logout } = useAuth();
  const queryClient = useQueryClient();
  const [endingId, setEndingId] = useState<number | null>(null);

  const currentSessionId = getSessionId();
  const isCurrentSession = (session: ActiveDevice) => String(session.id) === String(currentSessionId);

  const query = useQuery({
    queryKey: DEVICES,
    queryFn: async () => {
      try {
        return await authService.listDevices();
      } catch (error) {
        console.error('Error:', error);
        toast.error(t('devices.messages.loadError'));
        throw error;
      }
    },
  });

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
      await queryClient.invalidateQueries({ queryKey: DEVICES });
    } catch (error) {
      console.error('Error:', error);
      toast.error(t('devices.messages.endError'));
    } finally {
      setEndingId(null);
    }
  };

  return { sessions: query.data ?? NONE, isLoading: query.isFetching, endingId, isCurrentSession, endSession };
}
