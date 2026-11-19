import { ApiError } from '../services/apiError';
import { cryptoApi } from '../services/cryptoApi';
import { readLocalDevice, saveLocalDevice, wipeLocalKeys } from './keyStore';

// A browser keeps its device across sign-outs; each new sign-in and each refresh moves the device onto it.
export async function attachThisBrowser(sessionId: number | null, userId?: number): Promise<void> {
  try {
    const local = await readLocalDevice();
    if (!local) {
      return;
    }
    if (userId !== undefined && local.userId !== userId) {
      wipeLocalKeys();
      return;
    }
    if (!sessionId || local.boundSessionId === sessionId) {
      return;
    }
    await cryptoApi.attachSession(local.deviceId, sessionId);
    await saveLocalDevice({ ...local, boundSessionId: sessionId });
  } catch (error) {
    if (error instanceof ApiError && error.code === 'CRYPTO_DEVICE_NOT_FOUND') {
      wipeLocalKeys();
      return;
    }
    console.warn('[Messaging] Could not attach this browser to its session:', (error as Error).message);
  }
}
