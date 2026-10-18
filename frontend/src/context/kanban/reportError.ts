import { toast } from 'react-toastify';
import type { Translate } from './types';

export const messageOf = (err: unknown): string => (err as Error).message;

export const reportError = (t: Translate, err: unknown): void => {
  toast.error(t('notifications.errorOccurred', { message: messageOf(err) }));
};
