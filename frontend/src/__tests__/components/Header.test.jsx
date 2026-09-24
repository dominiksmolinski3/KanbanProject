import React from 'react';
import { render, screen, fireEvent, act } from '@testing-library/react';
import '@testing-library/jest-dom';
import { MemoryRouter } from 'react-router-dom';
import Header from '../../components/Header';

const mockLogout = jest.fn();

jest.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key) => key
  })
}));

jest.mock('../../components/LanguageSwitcher', () => {
  return function MockLanguageSwitcher() {
    return <div data-testid="mock-language-switcher"></div>;
  };
});

jest.mock('../../components/BoardSwitcher', () => {
  return function MockBoardSwitcher() {
    return <div data-testid="mock-board-switcher"></div>;
  };
});

jest.mock('../../context/AuthContext', () => ({
  useAuth: jest.fn(() => ({
    logout: mockLogout,
    token: 'fake-token'
  }))
}));

describe('Header Component', () => {
  const renderHeader = (path = '/board') => {
    return render(
      <MemoryRouter initialEntries={[path]}>
        <Header />
      </MemoryRouter>
    );
  };

  beforeEach(() => {
    global.window.scrollY = 0;
    mockLogout.mockClear();
  });

  test('renders the brand, the board switcher and the page links', () => {
    renderHeader();
    expect(screen.getByText('board.title').closest('a')).toHaveAttribute('href', '/');
    expect(screen.getByTestId('mock-board-switcher')).toBeInTheDocument();
    expect(screen.getByText('header.board').closest('a')).toHaveAttribute('href', '/board');
    expect(screen.getByText('header.users').closest('a')).toHaveAttribute('href', '/users');
    expect(screen.getByText('header.activity').closest('a')).toHaveAttribute('href', '/activity');
    expect(screen.getByText('header.flow').closest('a')).toHaveAttribute('href', '/flow');
  });

  test('leaves board-only actions to the board toolbar', () => {
    renderHeader();
    expect(screen.queryByText('header.addTask')).not.toBeInTheDocument();
    expect(screen.queryByText('header.wipLimit')).not.toBeInTheDocument();
    expect(screen.queryByText('header.addBoardItem')).not.toBeInTheDocument();
  });

  test('marks the page being shown', () => {
    renderHeader('/activity');
    expect(screen.getByText('header.activity').closest('a')).toHaveClass('active');
    expect(screen.getByText('header.board').closest('a')).not.toHaveClass('active');
  });

  test('keeps language, sessions and logout behind the settings menu', () => {
    renderHeader();
    const toggle = screen.getByTestId('header-menu-toggle');
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByTestId('mock-language-switcher')).not.toBeInTheDocument();
    expect(screen.queryByText('header.logout')).not.toBeInTheDocument();

    fireEvent.click(toggle);

    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByTestId('mock-language-switcher')).toBeInTheDocument();
    expect(screen.getByText('header.sessions').closest('a')).toHaveAttribute('href', '/sessions');
    expect(screen.getByText('header.logout')).toBeInTheDocument();
  });

  test('closes the settings menu on Escape and on a click outside it', () => {
    renderHeader();
    fireEvent.click(screen.getByTestId('header-menu-toggle'));
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByText('header.logout')).not.toBeInTheDocument();

    fireEvent.click(screen.getByTestId('header-menu-toggle'));
    fireEvent.mouseDown(document.body);
    expect(screen.queryByText('header.logout')).not.toBeInTheDocument();
  });

  test('closes the settings menu when a link in it is followed', () => {
    renderHeader();
    fireEvent.click(screen.getByTestId('header-menu-toggle'));
    fireEvent.click(screen.getByText('header.sessions'));
    expect(screen.queryByText('header.logout')).not.toBeInTheDocument();
  });

  test('logs out from the settings menu', () => {
    renderHeader();
    fireEvent.click(screen.getByTestId('header-menu-toggle'));
    fireEvent.click(screen.getByText('header.logout'));
    expect(mockLogout).toHaveBeenCalledTimes(1);
  });

  test('makes header sticky on scroll', async () => {
    renderHeader();
    const header = screen.getByRole('banner');
    expect(header).not.toHaveClass('sticky');
    await act(async () => {
      global.window.scrollY = 100;
      global.window.dispatchEvent(new Event('scroll'));
    });
    expect(header).toHaveClass('sticky');
    await act(async () => {
      global.window.scrollY = 0;
      global.window.dispatchEvent(new Event('scroll'));
    });
    expect(header).not.toHaveClass('sticky');
  });

  test('cleans up scroll event listener on unmount', () => {
    const removeEventListenerSpy = jest.spyOn(window, 'removeEventListener');

    const { unmount } = renderHeader();
    unmount();
    expect(removeEventListenerSpy).toHaveBeenCalledWith('scroll', expect.any(Function));
    removeEventListenerSpy.mockRestore();
  });
});
