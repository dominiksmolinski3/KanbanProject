import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import Invitations from '../../components/Invitations';

jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key, values) => (values ? `${key}:${values.name}` : key) })
}));

const mockKanban = {
  myInvitations: [],
  acceptInvitation: jest.fn(),
  declineInvitation: jest.fn()
};

jest.mock('../../context/KanbanContext', () => ({
  useKanban: () => mockKanban
}));

const invitation = (overrides = {}) => ({
  id: 7,
  boardId: 3,
  boardName: 'Delivery',
  email: 'me@example.com',
  invitedByName: 'Ada',
  status: 'PENDING',
  ...overrides
});

describe('Invitations', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockKanban.myInvitations = [invitation()];
  });

  test('names the board and who sent it, because an offer from nobody is not one you can answer', () => {
    render(<Invitations />);

    expect(screen.getByText('Delivery')).toBeInTheDocument();
    expect(screen.getByText('boards.invitations.from:Ada')).toBeInTheDocument();
  });

  test('accepting and declining each answer the one invitation they belong to', async () => {
    mockKanban.myInvitations = [invitation(), invitation({ id: 8, boardName: 'Support' })];
    render(<Invitations />);

    fireEvent.click(screen.getAllByText('boards.invitations.accept')[1]);
    await waitFor(() => expect(mockKanban.acceptInvitation).toHaveBeenCalledWith(8));

    fireEvent.click(screen.getAllByText('boards.invitations.decline')[0]);
    await waitFor(() => expect(mockKanban.declineInvitation).toHaveBeenCalledWith(7));
  });

  /*
   * The panel is on a page people visit for other reasons, so an empty one would be a heading
   * about invitations on every visit. The badge on the board switcher is what says there is
   * something here; this renders only when there is.
   */
  test('nothing is rendered when there is nothing outstanding', () => {
    mockKanban.myInvitations = [];
    const { container } = render(<Invitations />);

    expect(container).toBeEmptyDOMElement();
  });

  test('an absent list is treated as an empty one rather than thrown on', () => {
    mockKanban.myInvitations = undefined;
    const { container } = render(<Invitations />);

    expect(container).toBeEmptyDOMElement();
  });
});
