import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import App from '../App';
import { useAuth } from '../context/AuthContext';

jest.mock('../services/apiInterceptor', () => ({
  setupApiInterceptors: jest.fn(),
}));
jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key) => key }),
}));
jest.mock('../components/HomePage', () => () => <div>HomePage</div>);
jest.mock('../components/NotFound', () => () => <div>NotFound</div>);
jest.mock('../components/PageLoading', () => () => <div>PageLoading</div>);
// Renders its Outlet, so each protected route's own lazy boundary is actually crossed - a layout
// mock that swallowed its children proved the route existed and never that it rendered anything.
jest.mock('../components/ProtectedLayout', () => {
  const { Outlet } = jest.requireActual('react-router-dom');
  return () => <div>ProtectedLayout<Outlet /></div>;
});
jest.mock('../components/BoardPage', () => () => <div>BoardPage</div>);
jest.mock('../components/UsersManagement', () => () => <div>UsersManagement</div>);
jest.mock('../components/ActivityFeed', () => () => <div>ActivityFeed</div>);
jest.mock('../components/Devices', () => () => <div>Devices</div>);
jest.mock('../components/FlowMetrics', () => () => <div>FlowMetrics</div>);
jest.mock('../context/AuthContext', () => ({
  useAuth: jest.fn(),
  AuthProvider: ({ children }) => <div data-testid="auth-provider">{children}</div>,
}));

const navigateTo = (path) => window.history.pushState({}, '', path);

describe('App routing', () => {
  beforeEach(() => {
    useAuth.mockReturnValue({ token: null, isLoading: false });
  });

  test('/ renders HomePage outside any lazy boundary', async () => {
    navigateTo('/');
    render(<App />);
    await waitFor(() => expect(screen.getByText('HomePage')).toBeInTheDocument());
  });

  test('an unknown path renders the NotFound catch-all', async () => {
    navigateTo('/this-does-not-exist');
    render(<App />);
    await waitFor(() => expect(screen.getByText('NotFound')).toBeInTheDocument());
  });

  test('/board is a lazily-loaded route behind ProtectedLayout', async () => {
    navigateTo('/board');
    render(<App />);
    await waitFor(() => expect(screen.getByText('ProtectedLayout')).toBeInTheDocument());
  });

  test.each([
    ['/board', 'BoardPage'],
    ['/users', 'UsersManagement'],
    ['/activity', 'ActivityFeed'],
    ['/flow', 'FlowMetrics'],
    ['/sessions', 'Devices']
  ])('%s lazily loads %s inside the layout', async (path, screenName) => {
    navigateTo(path);
    render(<App />);
    await waitFor(() => expect(screen.getByText(screenName)).toBeInTheDocument());
  });
});
