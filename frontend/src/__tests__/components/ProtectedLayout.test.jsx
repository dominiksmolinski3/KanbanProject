import React from 'react';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
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
  KanbanProvider: ({ children, boardContents }) => (
    <div data-testid="kanban-provider" data-board-contents={String(boardContents)}>{children}</div>
  ),
}));
jest.mock('../../components/messages/MessagingLayer', () => ({ children }) => (
  <div data-testid="messaging-layer">{children}</div>
));
jest.mock('../../components/Header', () => () => <div>Header</div>);
jest.mock('../../components/Footer', () => () => <div>Footer</div>);
jest.mock('../../components/messages/MessageDock', () => () => <div>Dock</div>);

const renderAt = (initialPath) =>
  render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route element={<ProtectedLayout />}>
          <Route path="/board" element={<div>Board content</div>} />
          <Route path="/account" element={<div>Account content</div>} />
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
    expect(screen.getByTestId('messaging-layer')).toBeInTheDocument();
    expect(screen.getByText('Header')).toBeInTheDocument();
    expect(screen.getByText('Footer')).toBeInTheDocument();
    expect(screen.getByText('Dock')).toBeInTheDocument();
    expect(screen.getByText('Board content')).toBeInTheDocument();
  });

  test('asks for the board contents on the board page only', () => {
    useAuth.mockReturnValue({ token: 'jwt', isLoading: false });

    const board = renderAt('/board');
    expect(screen.getByTestId('kanban-provider')).toHaveAttribute('data-board-contents', 'true');
    board.unmount();

    renderAt('/account');
    expect(screen.getByText('Account content')).toBeInTheDocument();
    expect(screen.getByTestId('kanban-provider')).toHaveAttribute('data-board-contents', 'false');
  });

  test('gives the signed-in pages a query client', async () => {
    useAuth.mockReturnValue({ token: 'jwt', isLoading: false });
    const Page = () => {
      const { data } = useQuery({ queryKey: ['probe'], queryFn: async () => 'read' });
      return <div>{data ?? 'reading'}</div>;
    };

    render(
      <MemoryRouter initialEntries={['/board']}>
        <Routes>
          <Route element={<ProtectedLayout />}>
            <Route path="/board" element={<Page />} />
          </Route>
        </Routes>
      </MemoryRouter>
    );

    expect(await screen.findByText('read')).toBeInTheDocument();
  });
});
