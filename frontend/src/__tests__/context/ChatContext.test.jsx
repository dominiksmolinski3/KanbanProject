import React from 'react';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import '@testing-library/jest-dom';
import { ChatProvider, useChat } from '../../context/ChatContext';
import { toast } from 'react-toastify';
import ChatApi from '../../services/chatApi';
import { fetchBoardChatHistory, fetchDirectChatHistory } from '../../services/chatHistoryApi';

jest.mock('../../context/AuthContext', () => ({
  useAuth: jest.fn(),
}));
jest.mock('../../context/KanbanContext', () => ({
  useKanban: jest.fn(),
}));
jest.mock('react-toastify', () => ({
  toast: { error: jest.fn(), warning: jest.fn() },
}));
jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key) => key }),
}));
jest.mock('../../services/chatApi');
jest.mock('../../services/chatHistoryApi', () => ({
  fetchBoardChatHistory: jest.fn(),
  fetchDirectChatHistory: jest.fn(),
  DEFAULT_PAGE_SIZE: 25,
}));

const { useAuth } = jest.requireMock('../../context/AuthContext');
const { useKanban } = jest.requireMock('../../context/KanbanContext');

const TestComponent = () => {
  const chat = useChat();
  return (
    <div>
      <div>Open: {String(chat.isOpen)}</div>
      <div>Connected: {String(chat.isConnected)}</div>
      <div>Unread: {chat.unreadCount}</div>
      <div>Type: {chat.messageType}</div>
      <div>Messages: {chat.messages.map((m) => `${m.content}(${m.read})`).join(',')}</div>
      <div>Message: {chat.message}</div>
      <button onClick={chat.toggleChat}>Toggle</button>
      <button onClick={() => chat.setMessage('hello')}>SetMessage</button>
      <button onClick={chat.sendMessage}>Send</button>
      <button onClick={() => chat.setMessageType('private')}>SetPrivate</button>
      <button onClick={() => chat.setRecipient('bob@example.com')}>SetRecipient</button>
      <button onClick={chat.loadOlderMessages}>LoadOlder</button>
    </div>
  );
};

describe('ChatContext', () => {
  let chatApiInstance;

  beforeEach(() => {
    jest.clearAllMocks();
    chatApiInstance = {
      connect: jest.fn(() => Promise.resolve()),
      joinBoard: jest.fn(() => true),
      disconnect: jest.fn(() => true),
      sendMessage: jest.fn(() => true),
    };
    ChatApi.mockImplementation(() => chatApiInstance);
    useAuth.mockReturnValue({ user: { email: 'ada@example.com' }, token: 'jwt-token' });
    useKanban.mockReturnValue({ activeBoardId: 1 });
    fetchBoardChatHistory.mockResolvedValue({ messages: [], page: 0, totalPages: 1 });
    fetchDirectChatHistory.mockResolvedValue({ messages: [], page: 0, totalPages: 1 });
  });

  const renderChat = () => render(<ChatProvider><TestComponent /></ChatProvider>);

  test('opening the panel connects, joins the active board and loads history', async () => {
    renderChat();

    fireEvent.click(screen.getByText('Toggle'));

    await waitFor(() => expect(screen.getByText('Connected: true')).toBeInTheDocument());
    expect(chatApiInstance.connect).toHaveBeenCalledWith('jwt-token');
    expect(chatApiInstance.joinBoard).toHaveBeenCalledWith(1);
    expect(fetchBoardChatHistory).toHaveBeenCalled();
  });

  test('with no signed-in user, opening the panel does not connect', async () => {
    useAuth.mockReturnValue({ user: null, token: null });
    renderChat();

    fireEvent.click(screen.getByText('Toggle'));

    await waitFor(() => expect(screen.getByText('Open: true')).toBeInTheDocument());
    expect(chatApiInstance.connect).not.toHaveBeenCalled();
  });

  test('history loads oldest first and every entry is marked read on arrival', async () => {
    fetchBoardChatHistory.mockResolvedValue({
      messages: [
        { id: 2, content: 'second', timestamp: '2026-01-02T00:00:00Z' },
        { id: 1, content: 'first', timestamp: '2026-01-01T00:00:00Z' },
      ],
      page: 0,
      totalPages: 1,
    });
    renderChat();

    fireEvent.click(screen.getByText('Toggle'));

    await waitFor(() =>
      expect(screen.getByText('Messages: first(true),second(true)')).toBeInTheDocument()
    );
  });

  test('a message arriving while the panel is closed counts as unread; opening marks it read', async () => {
    renderChat();

    // Connect first (panel open), then close it, then simulate an inbound frame.
    fireEvent.click(screen.getByText('Toggle'));
    await waitFor(() => expect(screen.getByText('Connected: true')).toBeInTheDocument());
    fireEvent.click(screen.getByText('Toggle')); // close
    await waitFor(() => expect(screen.getByText('Open: false')).toBeInTheDocument());

    const onMessageReceived = ChatApi.mock.calls[0][0];
    act(() => {
      onMessageReceived({ body: JSON.stringify({ id: 99, content: 'ping', timestamp: '2026-01-01T00:00:00Z' }) });
    });

    await waitFor(() => expect(screen.getByText('Unread: 1')).toBeInTheDocument());
    expect(screen.getByText('Messages: ping(false)')).toBeInTheDocument();

    fireEvent.click(screen.getByText('Toggle')); // reopen
    await waitFor(() => expect(screen.getByText('Unread: 0')).toBeInTheDocument());
  });

  test('a refusal from the server surfaces the key it sent, translated by the client', async () => {
    renderChat();
    fireEvent.click(screen.getByText('Toggle'));
    await waitFor(() => expect(screen.getByText('Connected: true')).toBeInTheDocument());

    const onRefusal = ChatApi.mock.calls[0][2];
    act(() => {
      onRefusal({ reason: 'chat.errors.tooLong' });
    });

    expect(toast.warning).toHaveBeenCalledWith('chat.errors.tooLong');
  });

  test('sending a board message with nothing typed does nothing', async () => {
    renderChat();
    fireEvent.click(screen.getByText('Toggle'));
    await waitFor(() => expect(screen.getByText('Connected: true')).toBeInTheDocument());

    fireEvent.click(screen.getByText('Send'));

    expect(chatApiInstance.sendMessage).not.toHaveBeenCalled();
  });

  test('sending a private message with no recipient warns instead of sending', async () => {
    renderChat();
    fireEvent.click(screen.getByText('Toggle'));
    await waitFor(() => expect(screen.getByText('Connected: true')).toBeInTheDocument());
    fireEvent.click(screen.getByText('SetPrivate'));
    fireEvent.click(screen.getByText('SetMessage'));

    fireEvent.click(screen.getByText('Send'));

    expect(toast.warning).toHaveBeenCalledWith('chat.noRecipient');
    expect(chatApiInstance.sendMessage).not.toHaveBeenCalled();
  });

  test('a successful send clears the composed message', async () => {
    renderChat();
    fireEvent.click(screen.getByText('Toggle'));
    await waitFor(() => expect(screen.getByText('Connected: true')).toBeInTheDocument());
    fireEvent.click(screen.getByText('SetMessage'));
    await waitFor(() => expect(screen.getByText('Message: hello')).toBeInTheDocument());

    fireEvent.click(screen.getByText('Send'));

    expect(chatApiInstance.sendMessage).toHaveBeenCalledWith('board', 'hello', 1, '');
    await waitFor(() => expect(screen.getByText('Message:')).toBeInTheDocument());
  });

  test('switching to a private thread resets the conversation and reloads history', async () => {
    fetchBoardChatHistory.mockResolvedValue({
      messages: [{ id: 1, content: 'board msg', timestamp: '2026-01-01T00:00:00Z' }],
      page: 0,
      totalPages: 1,
    });
    renderChat();
    fireEvent.click(screen.getByText('Toggle'));
    await waitFor(() => expect(screen.getByText('Messages: board msg(true)')).toBeInTheDocument());

    fireEvent.click(screen.getByText('SetRecipient'));
    fireEvent.click(screen.getByText('SetPrivate'));

    await waitFor(() => expect(fetchDirectChatHistory).toHaveBeenCalled());
    await waitFor(() => expect(screen.getByText('Messages:')).toBeInTheDocument());
  });

  test('loadOlderMessages does nothing when there is no further history', async () => {
    renderChat();
    fireEvent.click(screen.getByText('Toggle'));
    await waitFor(() => expect(screen.getByText('Connected: true')).toBeInTheDocument());
    fetchBoardChatHistory.mockClear();

    fireEvent.click(screen.getByText('LoadOlder'));

    expect(fetchBoardChatHistory).not.toHaveBeenCalled();
  });
});
