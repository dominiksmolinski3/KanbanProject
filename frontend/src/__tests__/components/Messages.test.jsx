import { act, fireEvent, render, screen, waitFor, within } from '../../test-utils/rtl';
import '@testing-library/jest-dom';
import { MemoryRouter } from 'react-router-dom';
import Messages from '../../components/Messages';
import { MessagesProvider } from '../../context/MessagesContext';
import { messagesApi } from '../../services/messagesApi';
import { watchConversations } from '../../services/conversationEvents';
import { useMessagingKeys } from '../../components/devices/useMessagingKeys';
import ConversationList from '../../components/messages/ConversationList';
import Thread from '../../components/messages/Thread';

jest.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key, options) => (options && (options.name ?? options.count ?? options.emoji) !== undefined
      ? `${key}:${options.name ?? options.count ?? options.emoji}` : key),
    i18n: { language: 'en' },
  }),
}));

jest.mock('../../context/AuthContext', () => ({ useAuth: () => ({ user: { id: 1 } }) }));
jest.mock('../../components/devices/useMessagingKeys', () => ({ useMessagingKeys: jest.fn() }));
jest.mock('../../services/conversationEvents', () => ({ watchConversations: jest.fn(() => () => {}) }));
jest.mock('../../services/messagesApi', () => ({
  messagesApi: {
    list: jest.fn(), create: jest.fn(), contacts: jest.fn(), lookup: jest.fn(), update: jest.fn(),
    addMembers: jest.fn(), setRole: jest.fn(), removeMember: jest.fn(), accept: jest.fn(), markRead: jest.fn(),
    typing: jest.fn(), block: jest.fn(), markDelivered: jest.fn(),
  },
}));

const member = (userId, name, extra = {}) => ({
  userId, name, hasAvatar: false, role: 'MEMBER', state: 'ACTIVE', joinedAt: '2026-11-19T10:00:00Z', joinedSeq: 1,
  deliveredSeq: 3, readSeq: 3, ...extra,
});
const me = (extra = {}) => ({ role: 'ADMIN', state: 'ACTIVE', joinedSeq: 1, deliveredSeq: 3, readSeq: 1, muted: false,
  pinned: false,
  archived: false, ...extra });
const conversation = (id, kind, members, extra = {}) => ({
  id, kind, groupId: 'AA==', epoch: 1, needsCommit: false, historyVisibility: 'JOINED', whoCanAdd: 'ADMINS', lastSeq: 3,
  createdAt: '2026-11-19T10:00:00Z', lastActivityAt: '2026-11-19T10:00:00Z', unread: 0, me: me(), members, ...extra,
});

const ania = member(1, 'Ania Nowak', { role: 'ADMIN' });
const bartek = member(2, 'Bartek');
const celina = member(3, 'Celina', { state: 'REQUEST' });
const direct = conversation(5, 'DIRECT', [ania, { ...bartek, role: 'ADMIN' }], { unread: 2 });
const group = conversation(6, 'GROUP', [ania, bartek, celina], { me: me({ pinned: true }) });
const request = conversation(7, 'DIRECT', [ania, member(4, 'Darek')], { me: me({ state: 'REQUEST' }) });
const archived = conversation(8, 'DIRECT', [ania, member(9, 'Ewa')], { me: me({ archived: true }) });

const text = (seq, id, senderId, value, extra = {}) => ({
  conversationId: 5, seq, id, senderId, sentAt: '2026-11-19T10:00:00Z', kind: 'text', text: value, replyTo: null,
  editedAt: null, deleted: false, reactions: {}, notice: null, ...extra,
});

function fakeMessenger(overrides = {}) {
  return {
    device: jest.fn(async () => ({ userId: 1, deviceId: 10 })),
    acceptWelcomes: jest.fn(async () => []),
    sync: jest.fn(async () => ({ outcome: 'synced', items: [], cursor: 3 })),
    send: jest.fn(async (_conversation, payload) => (payload.t === 'text' ? [text(9, payload.id, 1, payload.text)] : [])),
    rename: jest.fn(async () => {}),
    checkTrust: jest.fn(async () => []),
    forgetDirectory: jest.fn(),
    directory: jest.fn(async () => []),
    history: jest.fn(async () => []),
    groupNames: jest.fn(async () => ({ 6: 'Launch team' })),
    verification: jest.fn(async () => ({ digits: '12345'.repeat(12), identityKey: 'key', verified: false })),
    setVerified: jest.fn(async () => {}),
    ...overrides,
  };
}

function renderMessages(messenger, initial = null) {
  return render(
    <MemoryRouter>
      <MessagesProvider messenger={messenger} initialConversationId={initial}>
        <ConversationList />
        <Thread />
      </MessagesProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  jest.clearAllMocks();
  messagesApi.list.mockResolvedValue([direct, group, request, archived]);
  messagesApi.contacts.mockResolvedValue([
    { id: 2, name: 'Bartek', email: 'b@example.com', hasAvatar: false, messaging: true },
    { id: 3, name: 'Celina', email: 'c@example.com', hasAvatar: false, messaging: false },
  ]);
  messagesApi.markRead.mockResolvedValue();
  messagesApi.typing.mockResolvedValue();
  messagesApi.markDelivered.mockResolvedValue();
  window.HTMLElement.prototype.scrollIntoView = jest.fn();
});

describe('the messages page', () => {
  test.each([
    ['off', 'messages.gate.off'],
    ['notSetUp', 'messages.gate.setUp'],
    ['elsewhere', 'messages.gate.unlock'],
  ])('when keys are %s it explains what to do', (state, message) => {
    useMessagingKeys.mockReturnValue({ state });
    render(<MemoryRouter><Messages /></MemoryRouter>);
    expect(screen.getByText(message)).toBeInTheDocument();
  });

  test('waits while the keys load, then shows the conversations', async () => {
    useMessagingKeys.mockReturnValue({ state: 'loading' });
    const { rerender } = render(<MemoryRouter><Messages /></MemoryRouter>);
    expect(screen.getByText('app.loading')).toBeInTheDocument();

    useMessagingKeys.mockReturnValue({ state: 'ready' });
    rerender(<MemoryRouter><Messages /></MemoryRouter>);
    expect(await screen.findByText('messages.empty.title', { selector: 'h2' })).toBeInTheDocument();
    expect((await screen.findAllByRole('button', { name: /Bartek/ })).length).toBeGreaterThan(0);
  });
});

describe('the conversation list', () => {
  test('puts requests first, pinned before the rest, keeps archived ones folded and searches by title', async () => {
    renderMessages(fakeMessenger());

    const requests = await screen.findByRole('region', { name: 'messages.list.requests' });
    expect(within(requests).getByRole('button', { name: /Darek/ })).toBeInTheDocument();
    const rows = screen.getAllByRole('button', { name: /Launch team|Bartek/ });
    expect(rows[0]).toHaveTextContent('Launch team');
    expect(screen.getByLabelText('messages.list.unread:2')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Ewa/ })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'messages.list.archived:1' }));
    expect(screen.getByRole('button', { name: /Ewa/ })).toBeInTheDocument();

    fireEvent.change(screen.getByPlaceholderText('messages.list.search'), { target: { value: 'launch' } });
    expect(screen.queryByRole('button', { name: /Bartek/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Launch team/ })).toBeInTheDocument();
  });

  test('says when there is nothing to show', async () => {
    messagesApi.list.mockResolvedValue([]);
    renderMessages(fakeMessenger());
    expect(await screen.findByText('messages.list.none')).toBeInTheDocument();
  });
});

describe('a thread', () => {
  test('shows the archive, then what sync brings, marks it read and runs the trust check', async () => {
    const messenger = fakeMessenger({
      history: jest.fn(async () => [text(2, 'a', 2, 'from the archive')]),
      sync: jest.fn(async () => ({ outcome: 'synced', items: [text(3, 'b', 1, 'just synced', { replyTo: 'a' })] })),
      checkTrust: jest.fn(async () => [{ ...text(3.001, 'n', null, ''), kind: 'notice',
        notice: { code: 'identity-changed', userId: 2 } }]),
    });
    renderMessages(messenger, 5);

    expect(await screen.findByText('from the archive', { selector: 'p' })).toBeInTheDocument();
    expect(await screen.findByText('just synced')).toBeInTheDocument();
    expect(await screen.findByText('messages.notice.identity-changed:Bartek')).toBeInTheDocument();
    expect(messagesApi.markRead).toHaveBeenCalledWith(5, 3);
    const quote = screen.getByText('just synced').closest('.message-bubble').querySelector('blockquote');
    expect(quote).toHaveTextContent('from the archive');
  });

  test('sends on Enter, keeps Shift+Enter as a new line, and tells the others it is typing', async () => {
    const messenger = fakeMessenger();
    renderMessages(messenger, 5);
    const field = await screen.findByLabelText('messages.composer.label');

    fireEvent.change(field, { target: { value: 'hello' } });
    fireEvent.keyDown(field, { key: 'Enter', shiftKey: true });
    expect(messenger.send).not.toHaveBeenCalled();
    fireEvent.keyDown(field, { key: 'Enter' });

    await waitFor(() => expect(messenger.send).toHaveBeenCalledWith(expect.objectContaining({ id: 5 }),
      expect.objectContaining({ t: 'text', text: 'hello' })));
    expect(await screen.findByText('hello')).toBeInTheDocument();
    expect(field).toHaveValue('');
    expect(messagesApi.typing).toHaveBeenCalledWith(5);
  });

  test('a failed send keeps the text and says so', async () => {
    const messenger = fakeMessenger({ send: jest.fn(async () => { throw new Error(''); }) });
    renderMessages(messenger, 5);
    const field = await screen.findByLabelText('messages.composer.label');

    fireEvent.change(field, { target: { value: 'lost?' } });
    fireEvent.click(screen.getByRole('button', { name: 'messages.composer.send' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('messages.errors.send');
    expect(field).toHaveValue('lost?');
  });

  test('replies, edits, reacts and deletes from a message\'s menu', async () => {
    const messenger = fakeMessenger({
      history: jest.fn(async () => [text(2, 'mine', 1, 'my words', { reactions: { '👍': [2] } }), text(3, 'theirs', 2, 'their words')]),
    });
    renderMessages(messenger, 5);
    await screen.findByText('their words');
    const menus = () => screen.getAllByRole('button', { name: 'messages.message.actions' });

    fireEvent.click(menus()[1]);
    expect(screen.queryByRole('menuitem', { name: 'messages.message.edit' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('menuitem', { name: 'messages.message.reply' }));
    expect(screen.getByText('messages.composer.replying:Bartek')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('messages.composer.label'), { target: { value: 'answer' } });
    fireEvent.click(screen.getByRole('button', { name: 'messages.composer.send' }));
    await waitFor(() => expect(messenger.send).toHaveBeenLastCalledWith(expect.anything(),
      expect.objectContaining({ t: 'text', replyTo: 'theirs' })));

    fireEvent.click(menus()[0]);
    fireEvent.click(screen.getByRole('menuitem', { name: 'messages.message.edit' }));
    const field = screen.getByLabelText('messages.composer.label');
    expect(field).toHaveValue('my words');
    fireEvent.change(field, { target: { value: 'better words' } });
    fireEvent.keyDown(field, { key: 'Enter' });
    await waitFor(() => expect(messenger.send).toHaveBeenLastCalledWith(expect.anything(),
      expect.objectContaining({ t: 'edit', target: 'mine', text: 'better words' })));

    fireEvent.click(screen.getByRole('button', { name: 'messages.message.reaction:1' }));
    await waitFor(() => expect(messenger.send).toHaveBeenLastCalledWith(expect.anything(),
      expect.objectContaining({ t: 'react', target: 'mine', emoji: '👍', on: true })));

    fireEvent.click(menus()[1]);
    fireEvent.click(screen.getByRole('menuitem', { name: 'messages.message.react:❤️' }));
    await waitFor(() => expect(messenger.send).toHaveBeenLastCalledWith(expect.anything(),
      expect.objectContaining({ t: 'react', target: 'theirs', emoji: '❤️', on: true })));

    fireEvent.click(menus()[0]);
    fireEvent.click(screen.getByRole('menuitem', { name: 'messages.message.delete' }));
    await waitFor(() => expect(messenger.send).toHaveBeenLastCalledWith(expect.anything(),
      expect.objectContaining({ t: 'delete', target: 'mine' })));
  });

  test('own messages show sent, delivered and read, counting only people who were there', async () => {
    const people = [ania, { ...bartek, deliveredSeq: 6, readSeq: 4 }, { ...celina, state: 'ACTIVE', joinedSeq: 6, deliveredSeq: 6, readSeq: 6 }];
    messagesApi.list.mockResolvedValue([conversation(6, 'GROUP', people, { lastSeq: 7 })]);
    renderMessages(fakeMessenger({
      history: jest.fn(async () => [
        text(4, 'r', 1, 'read by all'),
        text(5, 'd', 1, 'delivered to all'),
        text(7, 's', 1, 'only sent'),
        text(8, 'theirs', 2, 'not mine'),
        text(9, 'gone', 1, '', { deleted: true }),
      ]),
    }), 6);

    const statusOf = async (words) => (await screen.findByText(words)).closest('.message-bubble')
      .querySelector('.message-status')?.getAttribute('aria-label') ?? null;
    expect(await statusOf('read by all')).toBe('messages.status.read');
    expect(await statusOf('delivered to all')).toBe('messages.status.delivered');
    expect(await statusOf('only sent')).toBe('messages.status.sent');
    expect(await statusOf('not mine')).toBeNull();
    expect(screen.getByText('messages.message.deleted').closest('.message-bubble').querySelector('.message-status')).toBeNull();
  });

  test('a message to nobody else stays sent', async () => {
    messagesApi.list.mockResolvedValue([conversation(6, 'GROUP', [ania], { lastSeq: 2 })]);
    renderMessages(fakeMessenger({ history: jest.fn(async () => [text(2, 'alone', 1, 'just me')]) }), 6);
    expect((await screen.findByText('just me')).closest('.message-bubble').querySelector('.message-status'))
      .toHaveAttribute('aria-label', 'messages.status.sent');
  });

  test('conversations with news are synced in the background and reported as delivered', async () => {
    const behind = conversation(9, 'DIRECT', [ania, member(2, 'Bartek')], { lastSeq: 7, me: me({ deliveredSeq: 3 }) });
    const waiting = conversation(10, 'DIRECT', [ania, member(4, 'Darek')], { lastSeq: 2, me: me({ deliveredSeq: 0 }) });
    messagesApi.list.mockResolvedValue([direct, behind, waiting]);
    const messenger = fakeMessenger({
      sync: jest.fn(async (target) => (target.id === 9
        ? { outcome: 'synced', items: [text(7, 'x', 2, 'arrived')], cursor: 7 }
        : { outcome: 'waiting', items: [], cursor: 0 })),
    });
    renderMessages(messenger, 5);

    await waitFor(() => expect(messagesApi.markDelivered).toHaveBeenCalledWith(9, 7));
    expect(messenger.sync).toHaveBeenCalledWith(expect.objectContaining({ id: 10 }));
    expect(messagesApi.markDelivered).not.toHaveBeenCalledWith(10, expect.anything());
    expect(messagesApi.markDelivered).not.toHaveBeenCalledWith(5, expect.anything());
  });

  test('a background sync that fails reports nothing and is tried again on the next change', async () => {
    const behind = conversation(9, 'DIRECT', [ania, member(2, 'Bartek')], { lastSeq: 7, me: me({ deliveredSeq: 3 }) });
    messagesApi.list.mockResolvedValue([direct, behind]);
    const sync = jest.fn(async (target) => {
      if (target.id === 9) throw new Error('offline');
      return { outcome: 'synced', items: [], cursor: 3 };
    });
    renderMessages(fakeMessenger({ sync }), 5);

    await waitFor(() => expect(sync).toHaveBeenCalledWith(expect.objectContaining({ id: 9 })));
    messagesApi.list.mockResolvedValue([direct, { ...behind, lastSeq: 8 }]);
    const [handle] = watchConversations.mock.calls[0];
    await act(async () => handle({ type: 'MESSAGE', conversationId: 9, seq: 8, userId: 2 }));

    await waitFor(() => expect(sync.mock.calls.filter(([target]) => target.id === 9)).toHaveLength(2));
    expect(messagesApi.markDelivered).not.toHaveBeenCalled();
  });

  test('a draft is dropped with Escape or its close button', async () => {
    renderMessages(fakeMessenger({ history: jest.fn(async () => [text(2, 'x', 2, 'something')]) }), 5);
    await screen.findByText('something');
    const openReply = () => {
      fireEvent.click(screen.getByRole('button', { name: 'messages.message.actions' }));
      fireEvent.click(screen.getByRole('menuitem', { name: 'messages.message.reply' }));
    };

    openReply();
    fireEvent.keyDown(screen.getByLabelText('messages.composer.label'), { key: 'Escape' });
    expect(screen.queryByText('messages.composer.replying:Bartek')).not.toBeInTheDocument();
    openReply();
    fireEvent.click(screen.getByRole('button', { name: 'messages.composer.cancelDraft' }));
    expect(screen.queryByText('messages.composer.replying:Bartek')).not.toBeInTheDocument();
  });

  test('deleted messages, edits, notices and a missing reply all read sensibly', async () => {
    renderMessages(fakeMessenger({
      history: jest.fn(async () => [
        text(2, 'gone', 2, '', { deleted: true }),
        text(3, 'fixed', 2, 'fixed text', { editedAt: '2026-11-19T10:05:00Z', replyTo: 'nowhere' }),
        { ...text(3.001, 'joined', null, ''), kind: 'notice', notice: { code: 'joined', userId: 77 } },
      ]),
    }), 5);

    expect(await screen.findByText('messages.message.deleted')).toBeInTheDocument();
    expect(screen.getByText('messages.message.edited')).toBeInTheDocument();
    expect(screen.getByText('messages.message.replyMissing')).toBeInTheDocument();
    expect(screen.getByText('messages.notice.joined:messages.formerMember')).toBeInTheDocument();
  });

  test.each([
    ['waiting', 'messages.thread.waiting'],
    ['removed', 'messages.thread.removed'],
  ])('a %s thread says so and cannot be written in', async (outcome, message) => {
    renderMessages(fakeMessenger({ sync: jest.fn(async () => ({ outcome, items: [] })) }), 5);
    expect(await screen.findByText(message)).toBeInTheDocument();
    expect(screen.getByLabelText('messages.composer.label')).toBeDisabled();
  });

  test('a thread that fails to sync says so, and a missing conversation reads as removed', async () => {
    const failing = renderMessages(fakeMessenger({ sync: jest.fn(async () => { throw new Error('down'); }) }), 5);
    expect(await screen.findByText('messages.thread.failed')).toBeInTheDocument();
    failing.unmount();

    const { ApiError } = jest.requireActual('../../services/apiError');
    renderMessages(fakeMessenger({ sync: jest.fn(async () => { throw new ApiError('gone', { status: 404 }); }) }), 5);
    expect(await screen.findByText('messages.thread.removed')).toBeInTheDocument();
  });

  test('a request can be accepted, declined or blocked', async () => {
    messagesApi.accept.mockResolvedValue(request);
    messagesApi.removeMember.mockResolvedValue(true);
    messagesApi.block.mockResolvedValue();
    renderMessages(fakeMessenger(), 7);

    expect(await screen.findByText('messages.request.direct:Darek')).toBeInTheDocument();
    expect(screen.getByLabelText('messages.composer.label')).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'messages.request.accept' }));
    await waitFor(() => expect(messagesApi.accept).toHaveBeenCalledWith(7));
    fireEvent.click(screen.getByRole('button', { name: 'messages.request.block' }));
    await waitFor(() => expect(messagesApi.block).toHaveBeenCalledWith(4));
  });

  test('declining a request leaves it', async () => {
    messagesApi.removeMember.mockResolvedValue(true);
    renderMessages(fakeMessenger(), 7);
    fireEvent.click(await screen.findByRole('button', { name: 'messages.request.decline' }));
    await waitFor(() => expect(messagesApi.removeMember).toHaveBeenCalledWith(7, 1));
  });

  test('live events refresh the list, show who is typing and pick up welcomes', async () => {
    const messenger = fakeMessenger({ acceptWelcomes: jest.fn(async () => [6]) });
    renderMessages(messenger, 5);
    await screen.findByLabelText('messages.composer.label');
    const [handle, onLive] = watchConversations.mock.calls[0];

    act(() => handle({ type: 'TYPING', conversationId: 5, seq: null, userId: 2 }));
    expect(screen.getByText('messages.thread.typingOne:Bartek')).toBeInTheDocument();
    act(() => handle({ type: 'TYPING', conversationId: 5, seq: null, userId: 1 }));
    expect(screen.getByText('messages.thread.typingMany:2')).toBeInTheDocument();

    const before = messagesApi.list.mock.calls.length;
    await act(async () => handle({ type: 'MESSAGE', conversationId: 5, seq: 4, userId: 2 }));
    await act(async () => handle({ type: 'MEMBERS', conversationId: 5, seq: null, userId: 2 }));
    await act(async () => handle({ type: 'WELCOME', conversationId: 6, seq: 1, userId: 2 }));
    await act(async () => onLive());
    await waitFor(() => expect(messagesApi.list.mock.calls.length).toBeGreaterThan(before));
    expect(messenger.forgetDirectory).toHaveBeenCalledWith(5);
    expect(messenger.acceptWelcomes).toHaveBeenCalledTimes(2);
  });

  test('typing notices fade after a few seconds', async () => {
    jest.useFakeTimers();
    try {
      renderMessages(fakeMessenger(), 5);
      await act(async () => { await Promise.resolve(); });
      const [handle] = watchConversations.mock.calls[0];
      act(() => handle({ type: 'TYPING', conversationId: 5, seq: null, userId: 2 }));
      expect(screen.getByText('messages.thread.typingOne:Bartek')).toBeInTheDocument();
      act(() => jest.advanceTimersByTime(7000));
      expect(screen.queryByText('messages.thread.typingOne:Bartek')).not.toBeInTheDocument();
    } finally {
      jest.useRealTimers();
    }
  });

  test('going back on a phone clears the selection', async () => {
    renderMessages(fakeMessenger(), 5);
    fireEvent.click(await screen.findByRole('button', { name: 'messages.back' }));
    expect(screen.queryByLabelText('messages.composer.label')).not.toBeInTheDocument();
  });
});

describe('the details panel', () => {
  async function openDetails(messenger, id) {
    renderMessages(messenger, id);
    fireEvent.click(await screen.findByRole('button', { name: 'messages.details.open' }));
    return screen.getByRole('complementary', { name: 'messages.details.title' });
  }

  test('lets a group admin rename, set roles, remove people, change settings and preferences, and leave', async () => {
    messagesApi.setRole.mockResolvedValue(group);
    messagesApi.removeMember.mockResolvedValue(true);
    messagesApi.update.mockResolvedValue(group);
    const messenger = fakeMessenger();
    const panel = await openDetails(messenger, 6);

    await waitFor(() => expect(within(panel).getByLabelText('messages.details.name')).toBeInTheDocument());
    fireEvent.change(within(panel).getByLabelText('messages.details.name'), { target: { value: 'New name' } });
    fireEvent.click(within(panel).getByRole('button', { name: 'messages.details.rename' }));
    await waitFor(() => expect(messenger.rename).toHaveBeenCalledWith(expect.objectContaining({ id: 6 }), 'New name'));

    fireEvent.click(within(panel).getAllByRole('button', { name: 'messages.details.makeAdmin' })[0]);
    await waitFor(() => expect(messagesApi.setRole).toHaveBeenCalledWith(6, 2, 'ADMIN'));
    fireEvent.click(within(panel).getAllByRole('button', { name: 'messages.details.remove' })[1]);
    await waitFor(() => expect(messagesApi.removeMember).toHaveBeenCalledWith(6, 3));
    expect(within(panel).getByText('messages.details.invited')).toBeInTheDocument();

    fireEvent.change(within(panel).getByLabelText('messages.settings.history'), { target: { value: 'ALL' } });
    await waitFor(() => expect(messagesApi.update).toHaveBeenCalledWith(6, { historyVisibility: 'ALL' }));
    fireEvent.change(within(panel).getByLabelText('messages.settings.whoCanAdd'), { target: { value: 'MEMBERS' } });
    await waitFor(() => expect(messagesApi.update).toHaveBeenCalledWith(6, { whoCanAdd: 'MEMBERS' }));
    fireEvent.click(within(panel).getByLabelText('messages.details.muted'));
    await waitFor(() => expect(messagesApi.update).toHaveBeenCalledWith(6, { muted: true }));

    fireEvent.click(within(panel).getByRole('button', { name: 'messages.details.leave' }));
    await waitFor(() => expect(messagesApi.removeMember).toHaveBeenCalledWith(6, 1));
  });

  test('adds people from the contacts', async () => {
    messagesApi.addMembers.mockResolvedValue(group);
    messagesApi.contacts.mockResolvedValue([{ id: 11, name: 'Filip', email: 'f@example.com', hasAvatar: false, messaging: true }]);
    const messenger = fakeMessenger();
    const panel = await openDetails(messenger, 6);

    fireEvent.click(within(panel).getByRole('button', { name: 'messages.details.addPeople' }));
    fireEvent.click(await screen.findByRole('checkbox', { name: /Filip/ }));
    fireEvent.click(screen.getByRole('button', { name: 'messages.details.add' }));

    await waitFor(() => expect(messagesApi.addMembers).toHaveBeenCalledWith(6, [11]));
    expect(messenger.forgetDirectory).toHaveBeenCalledWith(6);
  });

  test('verifies a person by their security code', async () => {
    const messenger = fakeMessenger();
    const panel = await openDetails(messenger, 5);

    fireEvent.click(within(panel).getByRole('button', { name: 'messages.details.verify' }));
    const code = await screen.findByLabelText('messages.verify.number');
    expect(within(code).getAllByText('12345')).toHaveLength(12);
    fireEvent.click(screen.getByRole('button', { name: 'messages.verify.mark' }));
    await waitFor(() => expect(messenger.setVerified).toHaveBeenCalledWith(2, 'key', true));
    expect(await screen.findByText('messages.verify.verified')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'messages.close' }));
    expect(screen.queryByLabelText('messages.verify.number')).not.toBeInTheDocument();
  });

  test('explains when there is no security code yet', async () => {
    const panel = await openDetails(fakeMessenger({ verification: jest.fn(async () => null) }), 5);
    fireEvent.click(within(panel).getByRole('button', { name: 'messages.details.verify' }));
    expect(await screen.findByText('messages.verify.unavailable')).toBeInTheDocument();
  });

  test('blocks the other person of a direct conversation, and closes', async () => {
    messagesApi.block.mockResolvedValue();
    const panel = await openDetails(fakeMessenger(), 5);
    expect(within(panel).queryByRole('button', { name: 'messages.details.leave' })).not.toBeInTheDocument();
    fireEvent.click(within(panel).getByRole('button', { name: 'messages.details.block:Bartek' }));
    await waitFor(() => expect(messagesApi.block).toHaveBeenCalledWith(2));
  });

  test('closes, and a failed action says so', async () => {
    messagesApi.update.mockRejectedValue(new Error(''));
    const panel = await openDetails(fakeMessenger(), 5);
    fireEvent.click(within(panel).getByLabelText('messages.details.pinned'));
    expect(await within(panel).findByRole('alert')).toHaveTextContent('messages.errors.generic');
    fireEvent.click(within(panel).getByRole('button', { name: 'messages.close' }));
    expect(screen.queryByRole('complementary', { name: 'messages.details.title' })).not.toBeInTheDocument();
  });
});

describe('starting a conversation', () => {
  test('a direct conversation with a contact, whose name others cannot pick when they lack messages', async () => {
    messagesApi.create.mockResolvedValue(direct);
    renderMessages(fakeMessenger());
    fireEvent.click(await screen.findByRole('button', { name: 'messages.list.new' }));

    fireEvent.click(screen.getByRole('button', { name: 'messages.new.start' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('messages.new.pickSomeone');
    expect(await screen.findByRole('radio', { name: /Celina/ })).toBeDisabled();
    fireEvent.click(screen.getByRole('radio', { name: /Bartek/ }));
    fireEvent.click(screen.getByRole('button', { name: 'messages.new.start' }));

    await waitFor(() => expect(messagesApi.create).toHaveBeenCalledWith({ kind: 'DIRECT', userIds: [2] }));
    await waitFor(() => expect(screen.queryByRole('button', { name: 'messages.new.start' })).not.toBeInTheDocument());
  });

  test('a named group with someone found by email', async () => {
    messagesApi.create.mockResolvedValue(group);
    messagesApi.lookup.mockResolvedValue({ id: 12, name: 'Gosia', email: 'g@example.com', hasAvatar: false, messaging: true });
    const messenger = fakeMessenger();
    renderMessages(messenger);
    fireEvent.click(await screen.findByRole('button', { name: 'messages.list.new' }));

    fireEvent.click(screen.getByRole('button', { name: 'messages.new.group' }));
    fireEvent.change(screen.getByLabelText('messages.new.name'), { target: { value: 'Launch' } });
    fireEvent.change(screen.getByLabelText('messages.settings.history'), { target: { value: 'DAYS_30' } });
    fireEvent.change(screen.getByLabelText('messages.new.byEmail'), { target: { value: 'g@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: 'messages.new.find' }));
    expect(await screen.findByRole('checkbox', { name: /Gosia/ })).toBeChecked();
    fireEvent.change(screen.getByLabelText('messages.new.filter'), { target: { value: 'bar' } });
    fireEvent.click(screen.getByRole('checkbox', { name: /Bartek/ }));
    fireEvent.click(screen.getByRole('button', { name: 'messages.new.start' }));

    await waitFor(() => expect(messagesApi.create).toHaveBeenCalledWith(
      { kind: 'GROUP', userIds: [12, 2], historyVisibility: 'DAYS_30' }));
    expect(messenger.rename).toHaveBeenCalledWith(group, 'Launch');
  });

  test('an address nobody uses is reported, and cancelling closes the dialog', async () => {
    messagesApi.lookup.mockRejectedValue(new Error(''));
    renderMessages(fakeMessenger());
    fireEvent.click(await screen.findByRole('button', { name: 'messages.list.new' }));

    fireEvent.change(screen.getByLabelText('messages.new.byEmail'), { target: { value: 'nobody@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: 'messages.new.find' }));
    expect(await screen.findByText('messages.new.notFound')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'messages.cancel' }));
    expect(screen.queryByRole('button', { name: 'messages.new.find' })).not.toBeInTheDocument();
  });

  test('says when there is nobody to pick', async () => {
    messagesApi.contacts.mockResolvedValue([]);
    renderMessages(fakeMessenger());
    fireEvent.click(await screen.findByRole('button', { name: 'messages.list.new' }));
    expect(await screen.findByText('messages.new.noContacts')).toBeInTheDocument();
  });
});
