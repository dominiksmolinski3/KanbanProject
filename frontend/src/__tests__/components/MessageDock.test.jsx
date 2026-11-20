import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import '@testing-library/jest-dom';
import MessageDock from '../../components/messages/MessageDock';
import KanbanContext from '../../context/KanbanContext';
import { useMessages } from '../../context/MessagesContext';
import { useMessagingState } from '../../components/devices/useMessagingKeys';

jest.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key, options) => (options && (options.name ?? options.count) !== undefined
      ? `${key}:${options.name ?? options.count}` : key),
    i18n: { language: 'en' },
  }),
}));
jest.mock('../../components/devices/useMessagingKeys', () => ({ useMessagingState: jest.fn() }));
jest.mock('../../context/MessagesContext', () => ({ useMessages: jest.fn() }));
jest.mock('../../components/messages/Thread', () => ({ variant }) => <div data-testid="thread">{`thread:${variant}`}</div>);

const person = (userId, name) => ({ userId, name, role: 'MEMBER', state: 'ACTIVE' });
const conversation = (id, kind, extra = {}) => ({
  id, kind, boardId: null, boardName: null, unread: 0, lastActivityAt: `2026-11-2${id % 10}T10:00:00Z`,
  me: { role: 'MEMBER', state: 'ACTIVE', muted: false, archived: false, pinned: false },
  members: [person(1, 'Ania'), person(2, 'Bartek')], ...extra,
});

const channel = conversation(10, 'BOARD', { boardId: 4, boardName: 'Website relaunch', unread: 4 });
const otherChannel = conversation(11, 'BOARD', { boardId: 5, boardName: 'Another board' });
const zofia = conversation(3, 'DIRECT', { members: [person(1, 'Ania'), person(5, 'Zofia')], unread: 2 });
const marek = conversation(2, 'DIRECT', { members: [person(1, 'Ania'), person(6, 'Marek')] });
const old = conversation(1, 'DIRECT', { members: [person(1, 'Ania'), person(7, 'Old Olek')] });
const archived = conversation(9, 'DIRECT', { members: [person(1, 'Ania'), person(8, 'Archived Ela')],
  me: { role: 'MEMBER', state: 'ACTIVE', muted: false, archived: true, pinned: false } });
const request = conversation(8, 'DIRECT', { members: [person(1, 'Ania'), person(9, 'Request Rafał')],
  me: { role: 'MEMBER', state: 'REQUEST', muted: false, archived: false, pinned: false } });

let select;

function messages(overrides = {}) {
  return {
    conversations: [channel, otherChannel, old, marek, zofia, archived, request],
    selected: null,
    select,
    unread: 6,
    userId: 1,
    nameOf: () => null,
    ...overrides,
  };
}

function renderDock({ path = '/board', boardId = 4, value = messages() } = {}) {
  useMessages.mockReturnValue(value);
  return render(
    <MemoryRouter initialEntries={[path]}>
      <KanbanContext.Provider value={{ activeBoardId: boardId }}>
        <MessageDock />
      </KanbanContext.Provider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  select = jest.fn();
  useMessagingState.mockReturnValue({ state: 'ready' });
});

describe('the message dock', () => {
  test('is not there at all when encrypted messages are off', () => {
    useMessagingState.mockReturnValue({ state: 'off' });
    renderDock();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  test('is only a button with the unread count until opened, and does not open a thread by itself', () => {
    renderDock();

    expect(screen.getByRole('button', { name: 'messages.dock.open' })).toBeInTheDocument();
    expect(screen.getByLabelText('messages.list.unread:6')).toHaveTextContent('6');
    expect(screen.queryByTestId('thread')).not.toBeInTheDocument();
    expect(select).not.toHaveBeenCalled();
  });

  test('opens on the channel of the board being looked at, and shows the thread in its compact form', () => {
    renderDock();
    fireEvent.click(screen.getByRole('button', { name: 'messages.dock.open' }));

    expect(select).toHaveBeenCalledWith(10);
    expect(screen.getByRole('heading', { name: 'Website relaunch' })).toBeInTheDocument();
    expect(screen.getByText('messages.dock.boardChannel:2')).toBeInTheDocument();
  });

  test('waits for the board before opening anything, so a person is never opened in its place', () => {
    const { rerender } = renderDock({ boardId: null, value: messages() });
    fireEvent.click(screen.getByRole('button', { name: 'messages.dock.open' }));

    expect(select).not.toHaveBeenCalled();
    expect(screen.getByRole('heading', { name: 'messages.title' })).toBeInTheDocument();

    rerender(
      <MemoryRouter initialEntries={['/board']}>
        <KanbanContext.Provider value={{ activeBoardId: 4 }}><MessageDock /></KanbanContext.Provider>
      </MemoryRouter>,
    );
    expect(select).toHaveBeenCalledWith(10);
    expect(select).not.toHaveBeenCalledWith(3);
  });

  test('waits for the conversations to load before opening anything', () => {
    renderDock({ value: messages({ loading: true }) });
    fireEvent.click(screen.getByRole('button', { name: 'messages.dock.open' }));

    expect(select).not.toHaveBeenCalled();
    expect(screen.getByRole('heading', { name: 'messages.title' })).toBeInTheDocument();
  });

  test('opens on the board channel again each time, whatever was looked at before', () => {
    const value = messages({ selected: zofia });
    renderDock({ value });
    fireEvent.click(screen.getByRole('button', { name: 'messages.dock.open' }));
    fireEvent.click(screen.getByRole('button', { name: /Marek/ }));
    expect(select).toHaveBeenLastCalledWith(2);
    fireEvent.click(screen.getByRole('button', { name: 'messages.dock.close' }));
    select.mockClear();

    fireEvent.click(screen.getByRole('button', { name: 'messages.dock.open' }));

    expect(select).toHaveBeenCalledWith(10);
  });

  test('offers the board channel and the two most recent people, never archived chats or requests', () => {
    renderDock({ value: messages({ selected: channel }) });
    fireEvent.click(screen.getByRole('button', { name: 'messages.dock.open' }));

    const tabs = within(screen.getByRole('group', { name: 'messages.dock.recent' })).getAllByRole('button');
    expect(tabs.map((tab) => tab.textContent)).toEqual(['messages.dock.thisBoard4', 'ZZofia2', 'MMarek']);
    expect(screen.getByTestId('thread')).toHaveTextContent('thread:dock');
    expect(screen.queryByText(/Old Olek|Archived Ela|Request/)).not.toBeInTheDocument();
  });

  test('a tab opens its conversation', () => {
    renderDock({ value: messages({ selected: channel }) });
    fireEvent.click(screen.getByRole('button', { name: 'messages.dock.open' }));

    fireEvent.click(screen.getByRole('button', { name: /Zofia/ }));

    expect(select).toHaveBeenCalledWith(3);
  });

  test('follows the board: without a channel for it, the recent people are what is offered', () => {
    renderDock({ boardId: 99, value: messages({ selected: zofia }) });
    fireEvent.click(screen.getByRole('button', { name: 'messages.dock.open' }));

    const tabs = within(screen.getByRole('group', { name: 'messages.dock.recent' })).getAllByRole('button');
    expect(tabs.map((tab) => tab.textContent)).toEqual(['ZZofia2', 'MMarek']);
  });

  test('moves to the channel of another board when the board changes while it is open', () => {
    const value = messages({ selected: channel });
    const { rerender } = renderDock({ value });
    fireEvent.click(screen.getByRole('button', { name: 'messages.dock.open' }));
    select.mockClear();

    rerender(
      <MemoryRouter initialEntries={['/board']}>
        <KanbanContext.Provider value={{ activeBoardId: 5 }}><MessageDock /></KanbanContext.Provider>
      </MemoryRouter>,
    );

    expect(select).toHaveBeenCalledWith(11);
  });

  test('opens the same conversation in the full view, and closing it lets go of the selection', () => {
    renderDock({ value: messages({ selected: channel }) });
    fireEvent.click(screen.getByRole('button', { name: 'messages.dock.open' }));

    expect(screen.getByRole('link', { name: 'messages.dock.openFull' })).toHaveAttribute('href', '/messages?c=10');
    select.mockClear();
    fireEvent.click(screen.getByRole('button', { name: 'messages.dock.close' }));

    expect(select).toHaveBeenCalledWith(null);
    expect(screen.queryByTestId('thread')).not.toBeInTheDocument();
  });

  test('is out of the way on the messages page itself', () => {
    renderDock({ path: '/messages' });
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  test.each([
    ['notSetUp', 'messages.gate.setUp'],
    ['elsewhere', 'messages.gate.unlock'],
  ])('when keys are %s it says what to do and links to Signed-in devices', (state, message) => {
    useMessagingState.mockReturnValue({ state });
    renderDock({ value: messages({ conversations: [], unread: 0 }) });
    fireEvent.click(screen.getByRole('button', { name: 'messages.dock.open' }));

    expect(screen.getByText(message)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'messages.gate.open' })).toHaveAttribute('href', '/sessions');
    expect(screen.queryByTestId('thread')).not.toBeInTheDocument();
  });

  test('says when there is nothing to talk in yet', () => {
    renderDock({ boardId: 99, value: messages({ conversations: [], unread: 0 }) });
    fireEvent.click(screen.getByRole('button', { name: 'messages.dock.open' }));

    expect(screen.getByText('messages.dock.none')).toBeInTheDocument();
  });
});
