import { toast } from 'react-toastify';

export function readOnlyGuard(isViewer, t) {
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
