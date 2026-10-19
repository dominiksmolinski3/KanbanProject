import { QueryClient } from '@tanstack/react-query';
import { onSessionCleared } from './session';

export const createQueryClient = () =>
  new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } } });

export const queryClient = createQueryClient();

onSessionCleared(() => queryClient.clear());
