import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { render as rtlRender } from '@testing-library/react';
import { createQueryClient } from '../services/queryClient';

export * from '@testing-library/react';

export function render(ui, { client = createQueryClient(), ...options } = {}) {
  const Wrapper = ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  return { ...rtlRender(ui, { wrapper: Wrapper, ...options }), client };
}
