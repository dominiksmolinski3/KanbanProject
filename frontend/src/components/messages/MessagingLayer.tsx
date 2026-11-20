import type { ReactNode } from 'react';
import { MessagesProvider } from '../../context/MessagesContext';
import { useMessagingState } from '../devices/useMessagingKeys';

export default function MessagingLayer({ children }: { children: ReactNode }) {
  const { state } = useMessagingState();
  return <MessagesProvider enabled={state === 'ready'}>{children}</MessagesProvider>;
}
