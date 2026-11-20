import { useCallback, useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { deviceVault } from '../../crypto/client';
import { attachThisBrowser } from '../../crypto/attach';
import { ONE_TIME_PACKAGES, REFILL_BELOW } from '../../crypto/constants';
import { readLocalDevice, wipeLocalKeys } from '../../crypto/keyStore';
import { cryptoApi, type MessagingDevice } from '../../services/cryptoApi';
import { getSessionId } from '../../services/session';
import { useAuth } from '../../context/AuthContext';

export const MESSAGING_KEYS = ['messagingKeys'] as const;
export const MESSAGING_STATUS = [...MESSAGING_KEYS, 'status'] as const;
const STATUS = MESSAGING_STATUS;
const THIS_BROWSER = [...MESSAGING_KEYS, 'thisBrowser'] as const;

const NO_DEVICES: MessagingDevice[] = [];

export type MessagingState = 'off' | 'loading' | 'notSetUp' | 'elsewhere' | 'ready';

export function currentSessionId(): number | null {
  const id = Number(getSessionId());
  return Number.isInteger(id) && id > 0 ? id : null;
}

export function useMessagingState() {
  const { user } = useAuth();
  const queryClient = useQueryClient();

  const status = useQuery({ queryKey: STATUS, queryFn: cryptoApi.status });
  const local = useQuery({
    queryKey: THIS_BROWSER,
    queryFn: () => readLocalDevice(),
    enabled: status.data?.enabled === true,
  });

  const devices = status.data?.devices ?? NO_DEVICES;
  const thisDevice = local.data && local.data.userId === user?.id
    ? devices.find((device) => device.id === local.data?.deviceId)
    : undefined;
  const settled = !status.isFetching && !local.isFetching;
  const stale = settled && Boolean(local.data) && !thisDevice;

  useEffect(() => {
    if (stale) {
      wipeLocalKeys();
      queryClient.setQueryData(THIS_BROWSER, null);
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

  return { status, devices, thisDevice, stale, state };
}

export function useMessagingKeys() {
  const { t } = useTranslation();
  const { user } = useAuth();
  const queryClient = useQueryClient();
  const refilling = useRef(false);
  const attaching = useRef(false);
  const [removed, setRemoved] = useState(false);
  const { status, devices, thisDevice, stale, state } = useMessagingState();

  useEffect(() => {
    if (stale) setRemoved(true);
  }, [stale]);

  const refresh = useCallback(async () => {
    await queryClient.invalidateQueries({ queryKey: STATUS });
  }, [queryClient]);

  const registerBrowser = useCallback(async (register: (userId: number, sessionId: number) => Promise<unknown>) => {
    const sessionId = currentSessionId();
    if (!user || !sessionId) {
      throw new Error(t('devices.messaging.errors.noSession'));
    }
    await register(user.id, sessionId);
    await queryClient.invalidateQueries({ queryKey: STATUS });
    queryClient.setQueryData(THIS_BROWSER, await readLocalDevice());
    setRemoved(false);
  }, [user, t, queryClient]);

  const forget = useCallback(async (device: MessagingDevice) => {
    await cryptoApi.forgetDevice(device.id);
    if (device.id === thisDevice?.id) {
      wipeLocalKeys();
      queryClient.setQueryData(THIS_BROWSER, null);
    }
    await refresh();
  }, [thisDevice, queryClient, refresh]);

  useEffect(() => {
    const sessionId = currentSessionId();
    if (state !== 'ready' || !thisDevice || !sessionId || thisDevice.sessionId === sessionId || attaching.current) {
      return;
    }
    attaching.current = true;
    attachThisBrowser(sessionId, user?.id)
      .then(refresh)
      .finally(() => {
        attaching.current = false;
      });
  }, [state, thisDevice, user, refresh]);

  useEffect(() => {
    if (state !== 'ready' || !thisDevice || thisDevice.keyPackages >= REFILL_BELOW || refilling.current) {
      return;
    }
    refilling.current = true;
    deviceVault().prepareKeyPackages(ONE_TIME_PACKAGES - thisDevice.keyPackages)
      .then((packages) => cryptoApi.uploadKeyPackages(thisDevice.id, packages))
      .then(refresh)
      .catch((error) => console.warn('[Messaging] Could not top up key packages:', (error as Error).message))
      .finally(() => {
        refilling.current = false;
      });
  }, [state, thisDevice, refresh]);

  const keyForSession = useCallback(
    (sessionId: number): MessagingDevice | undefined => devices.find((device) => device.sessionId === sessionId),
    [devices],
  );

  const signedOutDevices = devices.filter((device) => device.sessionId === null && device.id !== thisDevice?.id);

  return {
    state,
    removed,
    thisDevice,
    identity: status.data?.identity ?? null,
    pin: status.data?.pin ?? null,
    signedOutDevices,
    keyForSession,
    registerBrowser,
    forget,
    refresh,
  };
}
