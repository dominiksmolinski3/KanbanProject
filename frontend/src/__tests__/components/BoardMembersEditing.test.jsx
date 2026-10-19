import React from 'react';
import { act, render, screen, fireEvent, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import BoardMembers from '../../components/BoardMembers';
import { fetchBoardInvitations } from '../../services/boardApi';

jest.mock('../../services/boardApi', () => ({ fetchBoardInvitations: jest.fn() }));
jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key, values) => (values ? `${key}:${values.name}` : key) }),
}));

const mockKanban = {
  activeBoard: null,
  renameBoard: jest.fn(),
  deleteBoard: jest.fn(),
  inviteToBoard: jest.fn(),
  revokeInvitation: jest.fn(),
  removeBoardMember: jest.fn(),
};
jest.mock('../../context/KanbanContext', () => ({ useKanban: () => mockKanban }));
jest.mock('../../context/AuthContext', () => ({ useAuth: () => ({ user: { id: 1 } }) }));

const board = {
  id: 3, name: 'Kanban', ownerId: 1, owned: true,
  members: [{ id: 1, name: 'Owner', email: 'owner@example.com' }, { id: 2, name: 'Member', email: 'member@example.com' }],
};

const renderPanel = async () => {
  await act(async () => { render(<BoardMembers />); });
};

const wait = (ms) => act(async () => { await new Promise((resolve) => setTimeout(resolve, ms)); });

describe('BoardMembers editing', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockKanban.activeBoard = board;
    mockKanban.renameBoard.mockResolvedValue(true);
    mockKanban.inviteToBoard.mockResolvedValue({ id: 7 });
    fetchBoardInvitations.mockResolvedValue([]);
    window.confirm = jest.fn(() => true);
  });

  test('cancelling a rename brings the heading back and sends nothing', async () => {
    await renderPanel();

    fireEvent.click(screen.getByText('boards.members.rename'));
    fireEvent.change(screen.getByDisplayValue('Kanban'), { target: { value: 'Roadmap' } });
    fireEvent.click(screen.getByText('boards.members.cancel'));

    expect(screen.getByRole('heading', { name: 'Kanban' })).toBeInTheDocument();
    expect(mockKanban.renameBoard).not.toHaveBeenCalled();
  });

  test('a rename that is refused keeps the form and what was typed', async () => {
    mockKanban.renameBoard.mockResolvedValue(false);
    await renderPanel();

    fireEvent.click(screen.getByText('boards.members.rename'));
    fireEvent.change(screen.getByDisplayValue('Kanban'), { target: { value: 'Roadmap' } });
    await act(async () => { fireEvent.click(screen.getByText('boards.members.save')); });

    expect(screen.getByDisplayValue('Roadmap')).toBeInTheDocument();
  });

  test('a rename to an accepted name closes the form', async () => {
    await renderPanel();

    fireEvent.click(screen.getByText('boards.members.rename'));
    fireEvent.change(screen.getByDisplayValue('Kanban'), { target: { value: 'Roadmap' } });
    await act(async () => { fireEvent.click(screen.getByText('boards.members.save')); });

    expect(screen.queryByDisplayValue('Roadmap')).not.toBeInTheDocument();
  });

  test('a blank name is not sent', async () => {
    await renderPanel();

    fireEvent.click(screen.getByText('boards.members.rename'));
    fireEvent.change(screen.getByDisplayValue('Kanban'), { target: { value: '   ' } });
    await act(async () => { fireEvent.click(screen.getByText('boards.members.save')); });
    await wait(20);

    expect(mockKanban.renameBoard).not.toHaveBeenCalled();
  });

  test('a blank address is not invited', async () => {
    await renderPanel();

    fireEvent.change(screen.getByLabelText('boards.invitations.inviteLabel'), { target: { value: '   ' } });
    await act(async () => { fireEvent.click(screen.getByText('boards.invitations.invite')); });
    await wait(20);

    expect(mockKanban.inviteToBoard).not.toHaveBeenCalled();
  });

  test('an invitation that is refused keeps the address, an accepted one empties the field and the role', async () => {
    mockKanban.inviteToBoard.mockResolvedValueOnce(null);
    await renderPanel();
    const field = screen.getByLabelText('boards.invitations.inviteLabel');
    const role = screen.getByLabelText('boards.invitations.roleLabel');

    fireEvent.change(field, { target: { value: 'new@example.com' } });
    fireEvent.change(role, { target: { value: 'VIEWER' } });
    await act(async () => { fireEvent.click(screen.getByText('boards.invitations.invite')); });
    expect(field).toHaveValue('new@example.com');
    expect(role).toHaveValue('VIEWER');

    await act(async () => { fireEvent.click(screen.getByText('boards.invitations.invite')); });
    await waitFor(() => expect(field).toHaveValue(''));
    expect(role).toHaveValue('MEMBER');
    expect(mockKanban.inviteToBoard).toHaveBeenLastCalledWith(3, 'new@example.com', 'VIEWER');
  });

  test('an accepted invitation reloads the pending list', async () => {
    await renderPanel();
    fetchBoardInvitations.mockClear();

    fireEvent.change(screen.getByLabelText('boards.invitations.inviteLabel'), { target: { value: 'new@example.com' } });
    await act(async () => { fireEvent.click(screen.getByText('boards.invitations.invite')); });

    await waitFor(() => expect(fetchBoardInvitations).toHaveBeenCalledWith(3));
  });

  test('a failed load of the invitations leaves the list empty rather than breaking the panel', async () => {
    fetchBoardInvitations.mockRejectedValue(new Error('down'));
    jest.spyOn(console, 'error').mockImplementation(() => {});

    await renderPanel();

    expect(screen.getByText('boards.members.explainer')).toBeInTheDocument();
    expect(screen.queryByText('boards.invitations.pendingHeading')).not.toBeInTheDocument();
  });
});
