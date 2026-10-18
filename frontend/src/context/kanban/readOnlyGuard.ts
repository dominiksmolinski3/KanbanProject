import { toast } from 'react-toastify';
import type { ReadOnlyGuard, Translate } from './types';

export function readOnlyGuard(isViewer: boolean, t: Translate): ReadOnlyGuard {
  const blockIfReadOnly = () => {
    if (isViewer) {
      toast.error(t('notifications.readOnlyBoard'));
      return true;
    }
    return false;
  };

  const refuseIfReadOnly = () => {
    if (isViewer) {
      const errorMessage = t('notifications.readOnlyBoard');
      toast.error(errorMessage);
      throw new Error(errorMessage);
    }
  };

  return { blockIfReadOnly, refuseIfReadOnly };
}
