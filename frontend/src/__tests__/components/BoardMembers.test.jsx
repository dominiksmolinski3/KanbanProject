import React from 'react';
import { act, render, screen, fireEvent, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import BoardMembers from '../../components/BoardMembers';
import { fetchBoardInvitations } from '../../services/boardApi';

jest.mock('../../services/boardApi', () => ({
  fetchBoardInvitations: jest.fn()
}));

jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key, values) => (values ? `${key}:${values.name}` : key) })
}));

const mockKanban = {
  activeBoard: null,
  renameBoard: jest.fn(),
  deleteBoard: jest.fn(),
  inviteToBoard: jest.fn(),
  revokeInvitation: jest.fn(),
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

/*
 * The panel loads its own pending-invitation list in an effect, so every render settles a promise.
 * Rendering through this keeps that inside act() instead of leaving each test to remember.
 */
const renderPanel = async () => {
  await act(async () => {
    render(<BoardMembers />);
  });
};

describe('BoardMembers', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockCurrentUser = { id: 1 };
    mockKanban.activeBoard = board();
    mockKanban.inviteToBoard.mockResolvedValue({ id: 7, email: 'new@example.com' });
    mockKanban.revokeInvitation.mockResolvedValue(true);
    fetchBoardInvitations.mockResolvedValue([]);
    window.confirm = jest.fn(() => true);
  });

  test('lists everyone who can see the board, and says which one is the owner', async () => {
    await renderPanel();

    expect(screen.getByText('Owner')).toBeInTheDocument();
    expect(screen.getByText('member@example.com')).toBeInTheDocument();
    expect(screen.getByText('boards.members.owner')).toBeInTheDocument();
    expect(screen.getByText('boards.members.member')).toBeInTheDocument();
  });

  test('the owner invites by email, and nobody joins the member list here', async () => {
    await renderPanel();

    fireEvent.change(screen.getByLabelText('boards.invitations.inviteLabel'), {
      target: { value: 'new@example.com' }
    });
    fireEvent.click(screen.getByText('boards.invitations.invite'));

    await waitFor(() =>
      expect(mockKanban.inviteToBoard).toHaveBeenCalledWith(3, 'new@example.com'));
    // The member list is what it was: an invitation is an offer, and the list only changes when
    // the other person accepts.
    expect(screen.getAllByText(/example\.com/).map(node => node.textContent))
      .toEqual(['owner@example.com', 'member@example.com']);
  });

  test('the form says the answer does not reveal who has an account', async () => {
    await renderPanel();

    // The server answers identically for a known and an unknown address, and the UI has to say
    // so - otherwise a blank result reads as a bug rather than as the point.
    expect(screen.getByText('boards.invitations.inviteNote')).toBeInTheDocument();
  });

  test('outstanding invitations are listed for the owner, and can be taken back', async () => {
    fetchBoardInvitations.mockResolvedValue([
      { id: 7, email: 'waiting@example.com', boardId: 3, status: 'PENDING' }
    ]);
    await renderPanel();

    expect(await screen.findByText('waiting@example.com')).toBeInTheDocument();
    fireEvent.click(screen.getByTitle('boards.invitations.revoke'));

    await waitFor(() => expect(mockKanban.revokeInvitation).toHaveBeenCalledWith(3, 7));
  });

  test('a member is not shown the invitation list - it is the owner who sees it', async () => {
    mockKanban.activeBoard = board({ owned: false });
    await renderPanel();

    await waitFor(() => expect(fetchBoardInvitations).not.toHaveBeenCalled());
    expect(screen.queryByText('boards.invitations.pendingHeading')).not.toBeInTheDocument();
  });

  test('the owner cannot be removed, by anyone', async () => {
    await renderPanel();

    // One remove button, and it is the member's - nothing can appoint a new owner, so a board
    // without one could never be renamed, shared or deleted again.
    const removals = screen.getAllByRole('button', { name: '×' });
    expect(removals).toHaveLength(1);

    fireEvent.click(removals[0]);
    expect(mockKanban.removeBoardMember).toHaveBeenCalledWith(3, 2);
  });

  test('a member sees the list but gets no owner controls', async () => {
    mockCurrentUser = { id: 2 };
    mockKanban.activeBoard = board({ owned: false });

    await renderPanel();

    expect(screen.queryByText('boards.invitations.inviteLabel')).not.toBeInTheDocument();
    expect(screen.queryByText('boards.members.delete')).not.toBeInTheDocument();
    expect(screen.getByText('boards.members.sharedWithYou')).toBeInTheDocument();
  });

  test('a member can still take themselves off the board', async () => {
    mockCurrentUser = { id: 2 };
    mockKanban.activeBoard = board({ owned: false });

    await renderPanel();

    fireEvent.click(screen.getByRole('button', { name: '×' }));
    expect(window.confirm).toHaveBeenCalledWith('boards.members.leaveConfirm');
    expect(mockKanban.removeBoardMember).toHaveBeenCalledWith(3, 2);
  });

  test('deleting names the board and what goes with it, and stops if the answer is no', async () => {
    window.confirm = jest.fn(() => false);
    await renderPanel();

    fireEvent.click(screen.getByText('boards.members.delete'));

    expect(window.confirm).toHaveBeenCalledWith('boards.members.deleteConfirm:Kanban');
    expect(mockKanban.deleteBoard).not.toHaveBeenCalled();
  });

  test('renaming replaces the heading with a form and sends the new name', async () => {
    await renderPanel();

    fireEvent.click(screen.getByText('boards.members.rename'));
    const input = screen.getByDisplayValue('Kanban');
    fireEvent.change(input, { target: { value: 'Roadmap' } });
    fireEvent.click(screen.getByText('boards.members.save'));

    await waitFor(() => expect(mockKanban.renameBoard).toHaveBeenCalledWith(3, 'Roadmap'));
  });

  test('renders nothing at all before a board has been resolved', async () => {
    mockKanban.activeBoard = null;
    const { container } = render(<BoardMembers />);

    expect(container).toBeEmptyDOMElement();
  });
});
