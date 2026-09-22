import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import BoardSwitcher from '../../components/BoardSwitcher';
import { useKanban } from '../../context/KanbanContext';

jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key, opts) => (opts ? `${key}:${JSON.stringify(opts)}` : key) }),
}));
jest.mock('../../context/KanbanContext', () => ({
  useKanban: jest.fn(),
}));

const baseContext = {
  boards: [],
  activeBoard: null,
  activeBoardId: null,
  selectBoard: jest.fn(),
  createBoard: jest.fn(),
  myInvitations: [],
};

describe('BoardSwitcher', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  test('renders nothing with no active board, no boards and no pending invitations', () => {
    useKanban.mockReturnValue({ ...baseContext });
    const { container } = render(<BoardSwitcher />);
    expect(container).toBeEmptyDOMElement();
  });

  test('a pending invitation is enough to show the switcher and its badge', () => {
    useKanban.mockReturnValue({ ...baseContext, myInvitations: [{ id: 1 }, { id: 2 }] });
    render(<BoardSwitcher />);
    expect(screen.getByTestId('invitation-badge')).toHaveTextContent('2');
  });

  test('shows the active board name on the toggle', () => {
    useKanban.mockReturnValue({
      ...baseContext,
      activeBoard: { id: 1, name: 'Roadmap' },
      activeBoardId: 1,
      boards: [{ id: 1, name: 'Roadmap', owned: true }],
    });
    render(<BoardSwitcher />);
    expect(screen.getByText('Roadmap')).toBeInTheDocument();
  });

  test('opens the menu, lists boards, and marks a shared one', () => {
    useKanban.mockReturnValue({
      ...baseContext,
      activeBoard: { id: 1, name: 'Mine' },
      activeBoardId: 1,
      boards: [
        { id: 1, name: 'Mine', owned: true },
        { id: 2, name: 'Theirs', owned: false },
      ],
    });
    render(<BoardSwitcher />);

    fireEvent.click(screen.getByRole('button', { expanded: false }));

    expect(screen.getByRole('menu')).toBeInTheDocument();
    expect(screen.getByText('Theirs')).toBeInTheDocument();
    expect(screen.getByText('boards.switcher.shared')).toBeInTheDocument();
  });

  test('selecting a board switches to it and closes the menu', () => {
    const selectBoard = jest.fn();
    useKanban.mockReturnValue({
      ...baseContext,
      selectBoard,
      activeBoard: { id: 1, name: 'Mine' },
      activeBoardId: 1,
      boards: [
        { id: 1, name: 'Mine', owned: true },
        { id: 2, name: 'Theirs', owned: false },
      ],
    });
    render(<BoardSwitcher />);
    fireEvent.click(screen.getByRole('button', { expanded: false }));

    fireEvent.click(screen.getByText('Theirs'));

    expect(selectBoard).toHaveBeenCalledWith(2);
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });

  test('creating a board with a blank name does nothing', async () => {
    const createBoard = jest.fn();
    useKanban.mockReturnValue({
      ...baseContext,
      createBoard,
      activeBoard: { id: 1, name: 'Mine' },
      activeBoardId: 1,
      boards: [{ id: 1, name: 'Mine', owned: true }],
    });
    render(<BoardSwitcher />);
    fireEvent.click(screen.getByRole('button', { expanded: false }));
    fireEvent.click(screen.getByText('+ boards.switcher.newBoard'));

    fireEvent.submit(screen.getByText('boards.switcher.create'));

    expect(createBoard).not.toHaveBeenCalled();
  });

  test('creating a board with a name submits, then resets and closes', async () => {
    const createBoard = jest.fn().mockResolvedValue({ id: 3, name: 'New one' });
    useKanban.mockReturnValue({
      ...baseContext,
      createBoard,
      activeBoard: { id: 1, name: 'Mine' },
      activeBoardId: 1,
      boards: [{ id: 1, name: 'Mine', owned: true }],
    });
    render(<BoardSwitcher />);
    fireEvent.click(screen.getByRole('button', { expanded: false }));
    fireEvent.click(screen.getByText('+ boards.switcher.newBoard'));

    fireEvent.change(screen.getByPlaceholderText('boards.switcher.namePlaceholder'), {
      target: { value: 'New one' },
    });
    fireEvent.submit(screen.getByText('boards.switcher.create'));

    expect(createBoard).toHaveBeenCalledWith('New one');
    await waitFor(() => expect(screen.queryByRole('menu')).not.toBeInTheDocument());
  });

  test('a click outside the switcher closes the open menu', () => {
    useKanban.mockReturnValue({
      ...baseContext,
      activeBoard: { id: 1, name: 'Mine' },
      activeBoardId: 1,
      boards: [{ id: 1, name: 'Mine', owned: true }],
    });
    render(<div><BoardSwitcher /><button>Outside</button></div>);
    fireEvent.click(screen.getByRole('button', { expanded: false }));
    expect(screen.getByRole('menu')).toBeInTheDocument();

    fireEvent.mouseDown(screen.getByText('Outside'));

    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });
});
