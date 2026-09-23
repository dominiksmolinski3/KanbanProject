import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import '@testing-library/jest-dom';
import Chat from '../../components/Chat';
import { useChat } from '../../context/ChatContext';
import { useAuth } from '../../context/AuthContext';

jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key, opts) => (opts ? `${key}:${JSON.stringify(opts)}` : key) }),
}));
jest.mock('../../context/ChatContext', () => ({
  useChat: jest.fn(),
}));
jest.mock('../../context/AuthContext', () => ({
  useAuth: jest.fn(),
}));

beforeAll(() => {
  window.HTMLElement.prototype.scrollIntoView = jest.fn();
});

const baseChat = {
  isOpen: false,
  messages: [],
  message: '',
  isConnected: true,
  unreadCount: 0,
  recipient: '',
  messageType: 'board',
  hasMoreHistory: false,
  isLoadingHistory: false,
  toggleChat: jest.fn(),
  sendMessage: jest.fn(),
  loadOlderMessages: jest.fn(),
  reloadConversation: jest.fn(),
  setMessage: jest.fn(),
  setMessageType: jest.fn(),
  setRecipient: jest.fn(),
};

describe('Chat', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    useAuth.mockReturnValue({ user: { email: 'ada@example.com' } });
  });

  test('closed: renders the toggle button with no badge when there is nothing unread', () => {
    useChat.mockReturnValue({ ...baseChat });
    render(<Chat />);
    expect(screen.getByTitle('chat.openChat')).toBeInTheDocument();
    expect(screen.queryByText('1')).not.toBeInTheDocument();
  });

  test('closed: shows the unread count badge', () => {
    useChat.mockReturnValue({ ...baseChat, unreadCount: 3 });
    render(<Chat />);
    expect(screen.getByText('3')).toBeInTheDocument();
  });

  test('clicking the closed toggle opens the panel', () => {
    const toggleChat = jest.fn();
    useChat.mockReturnValue({ ...baseChat, toggleChat });
    render(<Chat />);
    fireEvent.click(screen.getByTitle('chat.openChat'));
    expect(toggleChat).toHaveBeenCalled();
  });

  test('open: shows the empty state with no messages', () => {
    useChat.mockReturnValue({ ...baseChat, isOpen: true });
    render(<Chat />);
    expect(screen.getByText('chat.noMessages')).toBeInTheDocument();
  });

  test('open: renders own, other and system messages distinctly', () => {
    useChat.mockReturnValue({
      ...baseChat,
      isOpen: true,
      messages: [
        { id: 1, type: 'CHAT', sender: 'ada@example.com', content: 'hi from me', timestamp: '2026-01-01T00:00:00Z' },
        { id: 2, type: 'CHAT', sender: 'bob@example.com', content: 'hi from bob', timestamp: '2026-01-01T00:01:00Z' },
        { id: 3, type: 'JOIN', sender: 'carol@example.com' },
      ],
    });
    const { container } = render(<Chat />);

    expect(container.querySelector('.own-message .message-content')).toHaveTextContent('hi from me');
    expect(container.querySelector('.other-message .message-content')).toHaveTextContent('hi from bob');
    expect(screen.getByText('chat.userJoined:{"user":"carol@example.com"}')).toBeInTheDocument();
  });

  test('open: switching to a private conversation reveals the recipient field', () => {
    const setMessageType = jest.fn();
    useChat.mockReturnValue({ ...baseChat, isOpen: true, setMessageType });
    render(<Chat />);

    fireEvent.change(screen.getByLabelText('chat.conversationKind'), { target: { value: 'private' } });

    expect(setMessageType).toHaveBeenCalledWith('private');
  });

  test('open: a private conversation shows the recipient input and reloads on blur', () => {
    const reloadConversation = jest.fn();
    const setRecipient = jest.fn();
    useChat.mockReturnValue({
      ...baseChat, isOpen: true, messageType: 'private', reloadConversation, setRecipient,
    });
    render(<Chat />);

    const input = screen.getByPlaceholderText('chat.recipient');
    fireEvent.change(input, { target: { value: 'bob@example.com' } });
    fireEvent.blur(input);

    expect(setRecipient).toHaveBeenCalledWith('bob@example.com');
    expect(reloadConversation).toHaveBeenCalled();
  });

  test('open: the send button is disabled until connected with a non-blank message', () => {
    useChat.mockReturnValue({ ...baseChat, isOpen: true, isConnected: false, message: 'hi' });
    render(<Chat />);
    expect(screen.getByText('chat.send')).toBeDisabled();
  });

  test('open: Enter without shift sends; Enter with shift does not', () => {
    const sendMessage = jest.fn();
    useChat.mockReturnValue({ ...baseChat, isOpen: true, message: 'hi', sendMessage });
    render(<Chat />);
    const textarea = screen.getByLabelText('chat.typingMessage');

    fireEvent.keyPress(textarea, { key: 'Enter', code: 'Enter', charCode: 13, shiftKey: true });
    expect(sendMessage).not.toHaveBeenCalled();

    fireEvent.keyPress(textarea, { key: 'Enter', code: 'Enter', charCode: 13, shiftKey: false });
    expect(sendMessage).toHaveBeenCalled();
  });

  test('open: the load-older button is shown only when there is more history, and disables while loading', () => {
    useChat.mockReturnValue({ ...baseChat, isOpen: true, hasMoreHistory: true, isLoadingHistory: true });
    render(<Chat />);
    expect(screen.getByText('chat.loadingHistory')).toBeDisabled();
  });
});
