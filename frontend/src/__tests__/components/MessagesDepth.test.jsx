import { act, fireEvent, render, screen, waitFor, within } from '../../test-utils/rtl';
import '@testing-library/jest-dom';
import { MemoryRouter } from 'react-router-dom';
import { MessagesProvider } from '../../context/MessagesContext';
import { messagesApi } from '../../services/messagesApi';
import { downloadFile, uploadFiles } from '../../context/messages/files';
import ConversationList from '../../components/messages/ConversationList';
import Thread from '../../components/messages/Thread';

jest.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key, options) => (options && (options.name ?? options.count ?? options.after ?? options.size) !== undefined
      ? `${key}:${options.name ?? options.count ?? options.after ?? options.size}` : key),
    i18n: { language: 'en' },
  }),
}));

jest.mock('../../context/AuthContext', () => ({ useAuth: () => ({ user: { id: 1 } }) }));
jest.mock('../../services/conversationEvents', () => ({ watchConversations: jest.fn(() => () => {}) }));
jest.mock('../../context/messages/files', () => ({ uploadFiles: jest.fn(), downloadFile: jest.fn() }));
jest.mock('../../services/messagesApi', () => ({
  messagesApi: {
    list: jest.fn(), update: jest.fn(), markRead: jest.fn(), typing: jest.fn(), markDelivered: jest.fn(),
    earlierInBoard: jest.fn(), earlierWith: jest.fn(),
  },
}));

const member = (userId, name, extra = {}) => ({
  userId, name, hasAvatar: false, role: 'MEMBER', state: 'ACTIVE', joinedAt: '2026-11-19T10:00:00Z', joinedSeq: 1,
  deliveredSeq: 3, readSeq: 3, ...extra,
});
const ania = member(1, 'Ania Nowak', { role: 'ADMIN' });
const bartek = member(2, 'Bartek');
const celina = member(3, 'Celina', { state: 'REQUEST' });
const group = (extra = {}) => ({
  id: 6, kind: 'GROUP', boardId: null, boardName: null, groupId: 'AA==', epoch: 1, needsCommit: false,
  historyVisibility: 'JOINED', whoCanAdd: 'ADMINS', lastSeq: 9, createdAt: '2026-11-19T10:00:00Z',
  lastActivityAt: '2026-11-19T10:00:00Z', unread: 0, disappearAfter: null,
  me: { role: 'ADMIN', state: 'ACTIVE', joinedSeq: 1, deliveredSeq: 9, readSeq: 9, muted: false, pinned: false,
    archived: false },
  members: [ania, bartek, celina], ...extra,
});
const message = (seq, id, senderId, value, extra = {}) => ({
  conversationId: 6, seq, id, senderId, sentAt: '2026-11-19T10:00:00Z', kind: 'text', text: value, replyTo: null,
  editedAt: null, deleted: false, reactions: {}, notice: null, ...extra,
});

function fakeMessenger(overrides = {}) {
  return {
    device: jest.fn(async () => ({ userId: 1, deviceId: 10 })),
    acceptWelcomes: jest.fn(async () => []),
    sync: jest.fn(async () => ({ outcome: 'synced', items: [], cursor: 9 })),
    send: jest.fn(async (_conversation, payload) => (payload.t === 'text' ? [message(10, payload.id, 1, payload.text)] : [])),
    rename: jest.fn(),
    checkTrust: jest.fn(async () => []),
    forgetDirectory: jest.fn(),
    directory: jest.fn(async () => []),
    history: jest.fn(async () => []),
    search: jest.fn(async () => []),
    groupNames: jest.fn(async () => ({ 6: 'Launch team' })),
    verification: jest.fn(),
    setVerified: jest.fn(),
    ...overrides,
  };
}

function renderThread(messenger, conversation = group()) {
  messagesApi.list.mockResolvedValue([conversation]);
  return render(
    <MemoryRouter>
      <MessagesProvider messenger={messenger} initialConversationId={6}>
        <ConversationList />
        <Thread />
      </MessagesProvider>
    </MemoryRouter>,
  );
}

const sentPayload = (messenger) => messenger.send.mock.calls.at(-1)[1];

beforeEach(() => {
  jest.clearAllMocks();
  messagesApi.markRead.mockResolvedValue();
  messagesApi.typing.mockResolvedValue();
  messagesApi.markDelivered.mockResolvedValue();
  messagesApi.earlierInBoard.mockResolvedValue({ messages: [], page: 0, size: 25, totalPages: 1, totalEntries: 0 });
  window.HTMLElement.prototype.scrollIntoView = jest.fn();
});

describe('mentions', () => {
  test('suggests the people in the chat, inserts the chosen name and sends their ids', async () => {
    const messenger = fakeMessenger();
    renderThread(messenger);
    const field = await screen.findByLabelText('messages.composer.label');

    fireEvent.change(field, { target: { value: 'Hi @ba', selectionStart: 6 } });
    const options = await screen.findAllByRole('option');
    expect(options).toHaveLength(1);
    expect(options[0]).toHaveTextContent('Bartek');
    fireEvent.keyDown(field, { key: 'Enter' });
    expect(field).toHaveValue('Hi @Bartek ');

    fireEvent.click(screen.getByRole('button', { name: 'messages.composer.send' }));

    await waitFor(() => expect(messenger.send).toHaveBeenCalled());
    expect(sentPayload(messenger)).toMatchObject({ t: 'text', text: 'Hi @Bartek', mentions: [2] });
  });

  test('never suggests somebody who has only been invited, and arrow keys move the choice', async () => {
    renderThread(fakeMessenger());
    const field = await screen.findByLabelText('messages.composer.label');

    fireEvent.change(field, { target: { value: '@', selectionStart: 1 } });

    const options = await screen.findAllByRole('option');
    expect(options.map((option) => option.textContent)).toEqual(['Bartek']);
    fireEvent.keyDown(field, { key: 'ArrowDown' });
    expect(screen.getAllByRole('option')[0]).toHaveAttribute('aria-selected', 'true');
  });

  test('highlights a mention of the reader and marks the chat in the list', async () => {
    const messenger = fakeMessenger({
      history: jest.fn(async () => [message(9, 'm9', 2, 'Look @Ania Nowak', { mentions: [1] })]),
    });
    renderThread(messenger, group({ unread: 1, me: { ...group().me, readSeq: 8, deliveredSeq: 9 } }));

    const mark = await screen.findByText('@Ania Nowak');
    expect(mark.tagName).toBe('MARK');
    expect(screen.getByRole('img', { name: 'messages.list.mentioned' })).toBeInTheDocument();
  });
});

describe('threads', () => {
  const thread = () => [
    message(2, 'root', 2, 'Which date?'),
    message(3, 'answer', 1, 'Friday works', { threadId: 'root' }),
  ];

  test('keeps replies out of the main list and opens them in a panel that replies into the thread', async () => {
    const messenger = fakeMessenger({ history: jest.fn(async () => thread()) });
    renderThread(messenger);

    expect(await screen.findByText('Which date?')).toBeInTheDocument();
    expect(screen.queryByText('Friday works')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'messages.thread.replies:1' }));

    const panel = await screen.findByRole('complementary', { name: 'messages.thread.panel' });
    expect(within(panel).getByText('Friday works')).toBeInTheDocument();
    fireEvent.change(within(panel).getByLabelText('messages.composer.label'), { target: { value: 'Or Monday' } });
    fireEvent.click(within(panel).getByRole('button', { name: 'messages.composer.send' }));

    await waitFor(() => expect(messenger.send).toHaveBeenCalled());
    expect(sentPayload(messenger)).toMatchObject({ t: 'text', text: 'Or Monday', thread: 'root' });
    fireEvent.click(within(panel).getByRole('button', { name: 'messages.close' }));
    expect(screen.queryByRole('complementary', { name: 'messages.thread.panel' })).not.toBeInTheDocument();
  });

  test('a message menu offers to reply in a thread', async () => {
    const messenger = fakeMessenger({ history: jest.fn(async () => [message(2, 'root', 2, 'Which date?')]) });
    renderThread(messenger);
    await screen.findByText('Which date?');

    fireEvent.click(screen.getByRole('button', { name: 'messages.message.actions' }));
    fireEvent.click(screen.getByRole('menuitem', { name: 'messages.message.replyInThread' }));

    expect(await screen.findByRole('complementary', { name: 'messages.thread.panel' })).toBeInTheDocument();
  });

  test('a reply whose first message is gone stays in the main list', async () => {
    renderThread(fakeMessenger({ history: jest.fn(async () => [thread()[1]]) }));

    expect(await screen.findByText('Friday works')).toBeInTheDocument();
  });
});

describe('files', () => {
  const attached = { blobId: 3, key: 'a2V5', name: 'plan.pdf', type: 'application/pdf', size: 2048 };

  test('uploads the chosen files and sends their references with the text', async () => {
    uploadFiles.mockResolvedValue([attached]);
    const messenger = fakeMessenger();
    renderThread(messenger);
    await screen.findByLabelText('messages.composer.label');
    const file = new File(['pdf'], 'plan.pdf', { type: 'application/pdf' });

    fireEvent.change(screen.getByTestId('composer-file-input'), { target: { files: [file] } });
    expect(screen.getByText('plan.pdf')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'messages.composer.send' }));

    await waitFor(() => expect(messenger.send).toHaveBeenCalled());
    expect(uploadFiles).toHaveBeenCalledWith(6, [file]);
    expect(sentPayload(messenger)).toMatchObject({ t: 'text', text: '', files: [attached] });
  });

  test('refuses a file that is too large and lets a chosen one be removed', async () => {
    renderThread(fakeMessenger());
    await screen.findByLabelText('messages.composer.label');
    const huge = new File(['x'], 'huge.bin');
    Object.defineProperty(huge, 'size', { value: 50 * 1024 * 1024 });

    fireEvent.change(screen.getByTestId('composer-file-input'), { target: { files: [huge] } });
    expect(screen.getByRole('alert')).toHaveTextContent('messages.files.tooLarge');

    fireEvent.change(screen.getByTestId('composer-file-input'), { target: { files: [new File(['a'], 'a.txt')] } });
    fireEvent.click(screen.getByRole('button', { name: 'messages.files.remove:a.txt' }));
    expect(screen.queryByText('a.txt')).not.toBeInTheDocument();
  });

  test('refuses more files than a message may carry', async () => {
    renderThread(fakeMessenger());
    await screen.findByLabelText('messages.composer.label');
    const many = Array.from({ length: 11 }, (_, index) => new File(['x'], `f${index}.txt`));

    fireEvent.change(screen.getByTestId('composer-file-input'), { target: { files: many } });

    expect(screen.getByRole('alert')).toHaveTextContent('messages.files.tooMany');
  });

  test('downloads an attachment on request and says when that fails', async () => {
    URL.createObjectURL = jest.fn(() => 'blob:file');
    URL.revokeObjectURL = jest.fn();
    downloadFile.mockResolvedValueOnce(new Blob(['pdf'])).mockRejectedValueOnce(new Error('gone'));
    const click = jest.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    renderThread(fakeMessenger({ history: jest.fn(async () => [message(2, 'f', 2, '', { files: [attached] })]) }));
    const button = await screen.findByRole('button', { name: 'messages.files.download:plan.pdf' });

    fireEvent.click(button);
    await waitFor(() => expect(click).toHaveBeenCalled());
    expect(downloadFile).toHaveBeenCalledWith(6, attached);

    fireEvent.click(button);
    expect(await screen.findByRole('alert')).toHaveTextContent('messages.files.failed');
    click.mockRestore();
  });

  test('shows an image attachment inline', async () => {
    URL.createObjectURL = jest.fn(() => 'blob:image');
    URL.revokeObjectURL = jest.fn();
    downloadFile.mockResolvedValue(new Blob(['png']));
    const image = { ...attached, name: 'photo.png', type: 'image/png' };
    renderThread(fakeMessenger({ history: jest.fn(async () => [message(2, 'i', 2, 'look', { files: [image] })]) }));

    expect(await screen.findByRole('img', { name: 'photo.png' })).toHaveAttribute('src', 'blob:image');
  });
});

describe('disappearing messages', () => {
  test('stamps a time to live on what is sent, and marks a message that will go', async () => {
    const messenger = fakeMessenger({
      history: jest.fn(async () => [message(2, 'old', 2, 'short lived', { expiresAt: '2099-01-01T00:00:00Z' })]),
    });
    renderThread(messenger, group({ disappearAfter: 3600 }));
    await screen.findByText('short lived');
    expect(screen.getByTitle('messages.disappear.title:messages.disappear.hour')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('messages.composer.label'), { target: { value: 'soon gone' } });
    fireEvent.click(screen.getByRole('button', { name: 'messages.composer.send' }));

    await waitFor(() => expect(messenger.send).toHaveBeenCalled());
    expect(sentPayload(messenger)).toMatchObject({ text: 'soon gone', ttl: 3600 });
  });

  test('removes a message from the screen once its time has passed', async () => {
    jest.useFakeTimers({ doNotFake: ['setImmediate', 'nextTick'] });
    try {
      const expiresAt = new Date(Date.now() + 20000).toISOString();
      renderThread(fakeMessenger({ history: jest.fn(async () => [message(2, 'm', 2, 'fleeting', { expiresAt })]) }));
      expect(await screen.findByText('fleeting')).toBeInTheDocument();

      await act(async () => { await jest.advanceTimersByTimeAsync(40000); });

      expect(screen.queryByText('fleeting')).not.toBeInTheDocument();
    } finally {
      jest.useRealTimers();
    }
  });

  test('an admin changes the timer from the details panel', async () => {
    messagesApi.update.mockResolvedValue(group());
    renderThread(fakeMessenger());
    fireEvent.click(await screen.findByRole('button', { name: 'messages.details.open' }));

    fireEvent.change(await screen.findByLabelText('messages.disappear.label'), { target: { value: '86400' } });

    await waitFor(() => expect(messagesApi.update).toHaveBeenCalledWith(6, { disappearAfter: 86400 }));
  });

  test('a member who is not an admin cannot change the timer of a group', async () => {
    renderThread(fakeMessenger(), group({ me: { ...group().me, role: 'MEMBER' } }));
    fireEvent.click(await screen.findByRole('button', { name: 'messages.details.open' }));

    await screen.findByLabelText('messages.details.title');
    expect(screen.queryByLabelText('messages.disappear.label')).not.toBeInTheDocument();
  });
});

describe('searching messages', () => {
  const hit = message(4, 'hit', 2, 'The roadmap draft', { conversationId: 6 });

  test('searches this device after a short pause and opens the conversation of a hit', async () => {
    const messenger = fakeMessenger({ search: jest.fn(async () => [hit]) });
    renderThread(messenger);
    const box = await screen.findByPlaceholderText('messages.list.search');

    fireEvent.change(box, { target: { value: 'road' } });

    expect(await screen.findByText('The roadmap draft')).toBeInTheDocument();
    expect(messenger.search).toHaveBeenCalledWith('road', [6], 50);
    fireEvent.click(screen.getByText('The roadmap draft'));
  });

  test('says when nothing was found and does not search for a single letter', async () => {
    const messenger = fakeMessenger();
    renderThread(messenger);
    const box = await screen.findByPlaceholderText('messages.list.search');

    fireEvent.change(box, { target: { value: 'r' } });
    expect(messenger.search).not.toHaveBeenCalled();
    fireEvent.change(box, { target: { value: 'zzz' } });

    expect(await screen.findByText('messages.list.noResults')).toBeInTheDocument();
  });

  test('a failed search shows no results', async () => {
    renderThread(fakeMessenger({ search: jest.fn(async () => { throw new Error('locked'); }) }));

    fireEvent.change(await screen.findByPlaceholderText('messages.list.search'), { target: { value: 'road' } });

    expect(await screen.findByText('messages.list.noResults')).toBeInTheDocument();
  });
});
