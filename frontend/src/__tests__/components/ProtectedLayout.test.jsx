import React from 'react';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import '@testing-library/jest-dom';
import ProtectedLayout from '../../components/ProtectedLayout';
import { useAuth } from '../../context/AuthContext';

jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key) => key }),
}));
jest.mock('../../context/AuthContext', () => ({
  useAuth: jest.fn(),
}));
jest.mock('../../context/KanbanContext', () => ({
  KanbanProvider: ({ children }) => <div data-testid="kanban-provider">{children}</div>,
}));
jest.mock('../../context/ChatContext', () => ({
  ChatProvider: ({ children }) => <div data-testid="chat-provider">{children}</div>,
}));
jest.mock('../../components/Header', () => () => <div>Header</div>);
jest.mock('../../components/Footer', () => () => <div>Footer</div>);
jest.mock('../../components/Chat', () => () => <div>Chat</div>);

const renderAt = (initialPath) =>
  render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route element={<ProtectedLayout />}>
          <Route path="/board" element={<div>Board content</div>} />
        </Route>
        <Route path="/" element={<div>Home</div>} />
      </Routes>
    </MemoryRouter>
  );

describe('ProtectedLayout', () => {
  test('shows the translated loading message while the session is being checked', () => {
    useAuth.mockReturnValue({ token: null, isLoading: true });

    renderAt('/board');

    expect(screen.getByText('auth.checkingSession')).toBeInTheDocument();
  });

  test('redirects home when there is no token once loading settles', () => {
    useAuth.mockReturnValue({ token: null, isLoading: false });

    renderAt('/board');

    expect(screen.getByText('Home')).toBeInTheDocument();
  });

  test('renders the shell and the matched child route once authenticated', () => {
    useAuth.mockReturnValue({ token: 'jwt', isLoading: false });

    renderAt('/board');

    expect(screen.getByTestId('kanban-provider')).toBeInTheDocument();
    expect(screen.getByTestId('chat-provider')).toBeInTheDocument();
    expect(screen.getByText('Header')).toBeInTheDocument();
    expect(screen.getByText('Footer')).toBeInTheDocument();
    expect(screen.getByText('Chat')).toBeInTheDocument();
    expect(screen.getByText('Board content')).toBeInTheDocument();
  });
});
