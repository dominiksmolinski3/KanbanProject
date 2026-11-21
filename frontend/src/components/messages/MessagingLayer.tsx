import { useEffect, type ReactNode } from 'react';
import { MessagesProvider } from '../../context/MessagesContext';
import { useMessagingState } from '../devices/useMessagingKeys';
import { openFromNotification } from '../devices/push';

export default function MessagingLayer({ children }: { children: ReactNode }) {
  const { state } = useMessagingState();
  const ready = state === 'ready';

  useEffect(() => {
    if (!ready) return undefined;
    let live = true;
    let stop: () => void = () => undefined;
    openFromNotification((conversationId) => window.location.assign(`/messages?c=${conversationId}`))
      .then((remove) => {
        if (live) stop = remove; else remove();
      }, () => undefined);
    return () => {
      live = false;
      stop();
    };
  }, [ready]);

  return <MessagesProvider enabled={ready}>{children}</MessagesProvider>;
}
