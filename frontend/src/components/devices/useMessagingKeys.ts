import { useCallback, useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { deviceVault } from '../../crypto/client';
import { ONE_TIME_PACKAGES, REFILL_BELOW } from '../../crypto/constants';
import { readLocalDevice, wipeLocalKeys } from '../../crypto/keyStore';
import { cryptoApi, type MessagingDevice } from '../../services/cryptoApi';
import { getSessionId } from '../../services/session';
import { useAuth } from '../../context/AuthContext';

export const MESSAGING_KEYS = ['messagingKeys'] as const;
const STATUS = [...MESSAGING_KEYS, 'status'] as const;
const THIS_BROWSER = [...MESSAGING_KEYS, 'thisBrowser'] as const;

const NO_DEVICES: MessagingDevice[] = [];

export type MessagingState = 'off' | 'loading' | 'notSetUp' | 'elsewhere' | 'ready';

export function useMessagingKeys() {
  const { t } = useTranslation();
  const { user } = useAuth();
  const queryClient = useQueryClient();
  const refilling = useRef(false);
  const [removed, setRemoved] = useState(false);

  const status = useQuery({ queryKey: STATUS, queryFn: cryptoApi.status });
  const local = useQuery({
    queryKey: THIS_BROWSER,
    queryFn: () => readLocalDevice(),
    enabled: status.data?.enabled === true,
  });

  const devices = status.data?.devices ?? NO_DEVICES;
  const thisDevice = local.data ? devices.find((device) => device.id === local.data?.deviceId) : undefined;
  const settled = !status.isFetching && !local.isFetching;
  const stale = settled && Boolean(local.data) && !thisDevice;

  useEffect(() => {
    if (stale) {
      wipeLocalKeys();
      queryClient.setQueryData(THIS_BROWSER, null);
      setRemoved(true);
    }
  }, [stale, queryClient]);

  let state: MessagingState;
  if (!status.data) {
    state = status.isError ? 'off' : 'loading';
  } else if (!status.data.enabled) {
    state = 'off';
  } else if (local.isPending || stale) {
    state = 'loading';
  } else if (thisDevice) {
    state = 'ready';
  } else {
    state = status.data.identity ? 'elsewhere' : 'notSetUp';
  }

  const registerThisBrowser = useCallback(async () => {
    const sessionId = Number(getSessionId());
    if (!user || !Number.isInteger(sessionId) || sessionId <= 0) {
      throw new Error(t('devices.messaging.errors.noSession'));
    }
    const vault = deviceVault();
    const prepared = await vault.prepareDevice(user.id);
    const registered = await cryptoApi.registerDevice(sessionId, prepared);
    const committed = await vault.commitDevice(registered.id);
    await queryClient.invalidateQueries({ queryKey: STATUS });
    queryClient.setQueryData(THIS_BROWSER, committed);
    setRemoved(false);
  }, [user, t, queryClient]);

  useEffect(() => {
    if (state !== 'ready' || !thisDevice || thisDevice.keyPackages >= REFILL_BELOW || refilling.current) {
      return;
    }
    refilling.current = true;
    deviceVault().prepareKeyPackages(ONE_TIME_PACKAGES - thisDevice.keyPackages)
      .then((packages) => cryptoApi.uploadKeyPackages(thisDevice.id, packages))
      .then(() => queryClient.invalidateQueries({ queryKey: STATUS }))
      .catch((error) => console.warn('[Messaging] Could not top up key packages:', (error as Error).message))
      .finally(() => {
        refilling.current = false;
      });
  }, [state, thisDevice, queryClient]);

  const keyForSession = useCallback(
    (sessionId: number): MessagingDevice | undefined => devices.find((device) => device.sessionId === sessionId),
    [devices],
  );

  return { state, removed, thisDevice, keyForSession, registerThisBrowser };
}
