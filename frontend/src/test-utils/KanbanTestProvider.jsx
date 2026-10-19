import React, { useState } from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { KanbanProvider as Provider } from '../context/KanbanContext';
import { createQueryClient } from '../services/queryClient';

export function KanbanProvider(props) {
  const [client] = useState(createQueryClient);
  return (
    <QueryClientProvider client={client}>
      <Provider {...props} />
    </QueryClientProvider>
  );
}
