import React, { createContext, useContext, useReducer, useEffect, useRef, useCallback } from 'react';
import { useAuth } from './AuthContext';
import { useKanban } from './KanbanContext';
import { toast } from 'react-toastify';
import { useTranslation } from 'react-i18next';
import ChatApi from '../services/chatApi';
import { fetchBoardChatHistory, fetchDirectChatHistory, DEFAULT_PAGE_SIZE } from '../services/chatHistoryApi';

const ChatContext = createContext();

/**
 * Chat is the board's conversation now, not a global room.
 *
 * Three things follow from that and are worth knowing before editing this file:
 *
 * - **The board comes from `KanbanContext`, never from a control here.** There was a room picker
 *   with `general`, `help` and `random` in it, which named topics the server had no opinion about.
 *   Switching boards switches the conversation, which is the only sense in which a conversation
 *   here can be switched.
 * - **Opening the panel loads history.** The messages existed all along; nothing read them. Older
 *   pages are fetched on request, newest page first, the same shape the activity feed uses.
 * - **A refusal arrives on its own queue and is a key, not a sentence**, so it goes through `t()`
 *   like every other user-facing string.
 */
const initialState = {
  isOpen: false,
  messages: [],
  message: '',
  isConnected: false,
  unreadCount: 0,
  recipient: '',
  messageType: 'board',
  historyPage: 0,
  hasMoreHistory: false,
  isLoadingHistory: false,
};

/** Oldest first, which is the order the panel reads in; the server pages newest first. */
const chronological = (messages) =>
  [...messages].sort((a, b) => new Date(a.timestamp) - new Date(b.timestamp));

function chatReducer(state, action) {
  switch (action.type) {
    case 'TOGGLE_CHAT':
      return { ...state, isOpen: !state.isOpen };
    case 'SET_CONNECTED':
      return { ...state, isConnected: action.payload };
    case 'SET_MESSAGE':
      return { ...state, message: action.payload };
    case 'ADD_MESSAGE':
      return {
        ...state,
        messages: [...state.messages, { ...action.payload, read: state.isOpen }],
        unreadCount: state.isOpen ? state.unreadCount : state.unreadCount + 1
      };
    case 'HISTORY_LOADING':
      return { ...state, isLoadingHistory: true };
    /*
     * History is marked read on arrival whatever the panel is doing: it is what was already said,
     * so counting it as unread would badge the button for a conversation nobody missed.
     */
    case 'HISTORY_LOADED':
      return {
        ...state,
        isLoadingHistory: false,
        historyPage: action.payload.page,
        hasMoreHistory: action.payload.page + 1 < action.payload.totalPages,
        messages: chronological([
          ...action.payload.messages.map((entry) => ({ ...entry, read: true })),
          ...state.messages,
        ]),
      };
    case 'HISTORY_FAILED':
      return { ...state, isLoadingHistory: false };
    case 'RESET_CONVERSATION':
      return { ...state, messages: [], historyPage: 0, hasMoreHistory: false, unreadCount: 0 };
    case 'MARK_MESSAGES_READ':
      return {
        ...state,
        messages: state.messages.map(msg => ({ ...msg, read: true })),
        unreadCount: 0
      };
    case 'SET_MESSAGE_TYPE':
      return { ...state, messageType: action.payload };
    case 'SET_RECIPIENT':
      return { ...state, recipient: action.payload };
    case 'RESET_MESSAGE':
      return { ...state, message: '' };
    default:
      return state;
  }
}

export function ChatProvider({ children }) {
  const [state, dispatch] = useReducer(chatReducer, initialState);
  const { user, token } = useAuth();
  const { activeBoardId, activeBoard } = useKanban();
  // FEAT-08: read-only means read-only consistently, so a viewer can watch the board's chat but
  // not post to it. This is the client's proactive refusal; ChatController.sendMessage refuses the
  // same way on the server regardless of what this does.
  const isBoardReadOnly = activeBoard?.role === 'VIEWER';
  const { t } = useTranslation();
  const chatApiRef = useRef(null);

  useEffect(() => {
    if (state.isOpen) {
      dispatch({ type: 'MARK_MESSAGES_READ' });
    }
  }, [state.isOpen]);

  const onMessageReceived = (payload) => {
    try {
      const receivedMessage = JSON.parse(payload.body);
      dispatch({
        type: 'ADD_MESSAGE',
        payload: {
          ...receivedMessage,
          id: receivedMessage.id ?? `live-${Date.now()}-${Math.random()}`,
          timestamp: receivedMessage.timestamp || new Date().toISOString(),
        }
      });
    } catch {
      // A frame that will not parse is one there is nothing to render from.
    }
  };

  const onError = (error) => {
    console.error('WebSocket error:', error);
    dispatch({ type: 'SET_CONNECTED', payload: false });
    toast.error(t('chat.connectionError'));
  };

  /** The server sends a key; the wording is the client's, in whichever of the nine is loaded. */
  const onRefusal = (refusal) => {
    toast.warning(t(refusal?.reason || 'chat.errors.notSent'));
  };

  const loadHistory = useCallback(async (page = 0) => {
    if (!user) return;
    // A direct thread needs somebody to be with; the server answers a blank one with a 400, and
    // there is nothing to show for a recipient field somebody has not finished typing.
    if (state.messageType === 'private' && !state.recipient.trim()) return;

    dispatch({ type: 'HISTORY_LOADING' });
    try {
      const results = state.messageType === 'private'
        ? await fetchDirectChatHistory({ peer: state.recipient, page, size: DEFAULT_PAGE_SIZE })
        : await fetchBoardChatHistory({ boardId: activeBoardId, page, size: DEFAULT_PAGE_SIZE });

      dispatch({ type: 'HISTORY_LOADED', payload: results });
    } catch {
      dispatch({ type: 'HISTORY_FAILED' });
      toast.error(t('chat.historyError'));
    }
  }, [user, activeBoardId, state.messageType, state.recipient, t]);

  const connect = useCallback(async () => {
    if (!user) return;

    try {
      chatApiRef.current = new ChatApi(onMessageReceived, onError, onRefusal);
      await chatApiRef.current.connect(token);
      chatApiRef.current.joinBoard(activeBoardId);
      dispatch({ type: 'SET_CONNECTED', payload: true });
    } catch (error) {
      onError(error);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user, token, activeBoardId]);

  const disconnect = useCallback(() => {
    if (!chatApiRef.current) return;

    const wasDisconnected = chatApiRef.current.disconnect();
    chatApiRef.current = null;
    if (wasDisconnected) {
      dispatch({ type: 'SET_CONNECTED', payload: false });
    }
  }, []);

  useEffect(() => {
    if (user && state.isOpen && !state.isConnected) {
      connect();
      loadHistory(0);
    }

    return () => {
      if (chatApiRef.current) {
        disconnect();
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user, state.isOpen]);

  /*
   * Switching boards switches the conversation. The socket is kept and only the subscription
   * moves, and the panel is emptied first so the previous board's messages do not read as this
   * board's - which is the whole point of the feature being board-scoped at all.
   */
  useEffect(() => {
    if (!state.isOpen || !state.isConnected || !chatApiRef.current) return;

    dispatch({ type: 'RESET_CONVERSATION' });
    if (state.messageType !== 'private') {
      chatApiRef.current.joinBoard(activeBoardId);
    }
    loadHistory(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeBoardId]);

  const sendMessage = () => {
    if (!chatApiRef.current || !state.isConnected || !state.message.trim()) return;

    if (state.messageType === 'private' && !state.recipient.trim()) {
      toast.warning(t('chat.noRecipient'));
      return;
    }

    if (state.messageType !== 'private' && (activeBoardId === null || activeBoardId === undefined)) {
      toast.warning(t('chat.noBoard'));
      return;
    }

    if (state.messageType !== 'private' && isBoardReadOnly) {
      toast.warning(t('chat.errors.readOnly'));
      return;
    }

    const sent = chatApiRef.current.sendMessage(
      state.messageType,
      state.message,
      activeBoardId,
      state.recipient
    );

    if (sent) {
      dispatch({ type: 'RESET_MESSAGE' });
    }
  };

  const setMessageType = (type) => {
    if (type === state.messageType) return;
    dispatch({ type: 'SET_MESSAGE_TYPE', payload: type });
    dispatch({ type: 'RESET_CONVERSATION' });
  };

  /*
   * Switching between the board and a direct thread switches which conversation is on screen, so
   * the loaded one follows. The recipient is not in this dependency list on purpose: reloading on
   * every keystroke would ask the server for a thread with `b`, then `bo`, then `bob` - the panel
   * asks for it once the address is finished instead, through `reloadConversation`.
   */
  useEffect(() => {
    if (!state.isOpen) return;
    loadHistory(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state.messageType]);

  const loadOlderMessages = () => {
    if (state.isLoadingHistory || !state.hasMoreHistory) return;
    loadHistory(state.historyPage + 1);
  };

  const toggleChat = () => {
    dispatch({ type: 'TOGGLE_CHAT' });
  };

  const value = {
    ...state,
    activeBoardId,
    isBoardReadOnly,
    toggleChat,
    sendMessage,
    connect,
    disconnect,
    loadOlderMessages,
    reloadConversation: () => {
      dispatch({ type: 'RESET_CONVERSATION' });
      loadHistory(0);
    },
    setMessage: (message) => dispatch({ type: 'SET_MESSAGE', payload: message }),
    setMessageType,
    setRecipient: (recipient) => dispatch({ type: 'SET_RECIPIENT', payload: recipient }),
  };

  return <ChatContext.Provider value={value}>{children}</ChatContext.Provider>;
}

export const useChat = () => {
  const context = useContext(ChatContext);
  if (!context) {
    throw new Error('useChat must be used within a ChatProvider');
  }
  return context;
};
