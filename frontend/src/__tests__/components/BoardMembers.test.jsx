import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import BoardMembers from '../../components/BoardMembers';

jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key, values) => (values ? `${key}:${values.name}` : key) })
}));

const mockKanban = {
  activeBoard: null,
  renameBoard: jest.fn(),
  deleteBoard: jest.fn(),
  addBoardMember: jest.fn(),
  removeBoardMember: jest.fn()
};
let mockCurrentUser = { id: 1 };

jest.mock('../../context/KanbanContext', () => ({
  useKanban: () => mockKanban
}));

jest.mock('../../context/AuthContext', () => ({
  useAuth: () => ({ user: mockCurrentUser })
}));

const owner = { id: 1, name: 'Owner', email: 'owner@example.com' };
const member = { id: 2, name: 'Member', email: 'member@example.com' };

const board = (overrides = {}) => ({
  id: 3,
  name: 'Kanban',
  ownerId: 1,
  owned: true,
  members: [owner, member],
  ...overrides
});

describe('BoardMembers', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockCurrentUser = { id: 1 };
    mockKanban.activeBoard = board();
    window.confirm = jest.fn(() => true);
  });

  test('lists everyone who can see the board, and says which one is the owner', () => {
    render(<BoardMembers />);

    expect(screen.getByText('Owner')).toBeInTheDocument();
    expect(screen.getByText('member@example.com')).toBeInTheDocument();
    expect(screen.getByText('boards.members.owner')).toBeInTheDocument();
    expect(screen.getByText('boards.members.member')).toBeInTheDocument();
  });

  test('the owner can add somebody by email', async () => {
    render(<BoardMembers />);

    fireEvent.change(screen.getByLabelText('boards.members.addLabel'), {
      target: { value: 'new@example.com' }
    });
    fireEvent.click(screen.getByText('boards.members.add'));

    await waitFor(() =>
      expect(mockKanban.addBoardMember).toHaveBeenCalledWith(3, 'new@example.com'));
  });

  test('the form says the answer does not reveal who has an account', () => {
    render(<BoardMembers />);

    // The server answers identically for a known and an unknown address, and the UI has to say
    // so - otherwise a blank result reads as a bug rather than as the point.
    expect(screen.getByText('boards.members.addNote')).toBeInTheDocument();
  });

  test('the owner cannot be removed, by anyone', () => {
    render(<BoardMembers />);

    // One remove button, and it is the member's - nothing can appoint a new owner, so a board
    // without one could never be renamed, shared or deleted again.
    const removals = screen.getAllByRole('button', { name: '×' });
    expect(removals).toHaveLength(1);

    fireEvent.click(removals[0]);
    expect(mockKanban.removeBoardMember).toHaveBeenCalledWith(3, 2);
  });

  test('a member sees the list but gets no owner controls', () => {
    mockCurrentUser = { id: 2 };
    mockKanban.activeBoard = board({ owned: false });

    render(<BoardMembers />);

    expect(screen.queryByText('boards.members.addLabel')).not.toBeInTheDocument();
    expect(screen.queryByText('boards.members.delete')).not.toBeInTheDocument();
    expect(screen.getByText('boards.members.sharedWithYou')).toBeInTheDocument();
  });

  test('a member can still take themselves off the board', () => {
    mockCurrentUser = { id: 2 };
    mockKanban.activeBoard = board({ owned: false });

    render(<BoardMembers />);

    fireEvent.click(screen.getByRole('button', { name: '×' }));
    expect(window.confirm).toHaveBeenCalledWith('boards.members.leaveConfirm');
    expect(mockKanban.removeBoardMember).toHaveBeenCalledWith(3, 2);
  });

  test('deleting names the board and what goes with it, and stops if the answer is no', () => {
    window.confirm = jest.fn(() => false);
    render(<BoardMembers />);

    fireEvent.click(screen.getByText('boards.members.delete'));

    expect(window.confirm).toHaveBeenCalledWith('boards.members.deleteConfirm:Kanban');
    expect(mockKanban.deleteBoard).not.toHaveBeenCalled();
  });

  test('renaming replaces the heading with a form and sends the new name', async () => {
    render(<BoardMembers />);

    fireEvent.click(screen.getByText('boards.members.rename'));
    const input = screen.getByDisplayValue('Kanban');
    fireEvent.change(input, { target: { value: 'Roadmap' } });
    fireEvent.click(screen.getByText('boards.members.save'));

    await waitFor(() => expect(mockKanban.renameBoard).toHaveBeenCalledWith(3, 'Roadmap'));
  });

  test('renders nothing at all before a board has been resolved', () => {
    mockKanban.activeBoard = null;
    const { container } = render(<BoardMembers />);

    expect(container).toBeEmptyDOMElement();
  });
});
