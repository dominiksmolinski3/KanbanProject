import { createContext, useCallback, useContext, useEffect, useMemo, useReducer, useRef, useState, type ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useAuth } from './AuthContext';
import { CONVERSATIONS_KEY } from './messages/queryKeys';
import { createMessenger, isGone, textPayload, type Messenger, type SyncOutcome } from './messages/messenger';
import { messagesApi, type Conversation, type ConversationSettings, type MemberRole, type NewConversation } from '../services/messagesApi';
import { watchConversations, type ConversationEvent } from '../services/conversationEvents';
import { newMessageId } from '../crypto/payload';
import type { ArchivedMessage } from '../crypto/engine';


const ARCHIVE_PAGE = 200;
const TYPING_MS = 5000;
const TYPING_THROTTLE_MS = 3000;

export type ThreadState = SyncOutcome | 'loading' | 'failed';

interface ThreadsState {
  messages: Record<number, ArchivedMessage[]>;
  states: Record<number, ThreadState>;
}

type ThreadsAction =
  | { type: 'merge'; conversationId: number; items: ArchivedMessage[] }
  | { type: 'state'; conversationId: number; state: ThreadState };

function merge(current: ArchivedMessage[], items: ArchivedMessage[]): ArchivedMessage[] {
  const byId = new Map(current.map((message) => [message.id, message]));
  items.forEach((item) => byId.set(item.id, item));
  return [...byId.values()].sort((a, b) => a.seq - b.seq);
}

function threads(state: ThreadsState, action: ThreadsAction): ThreadsState {
  if (action.type === 'merge') {
    if (!action.items.length && state.messages[action.conversationId]) return state;
    return {
      ...state,
      messages: { ...state.messages, [action.conversationId]: merge(state.messages[action.conversationId] ?? [], action.items) },
    };
  }
  return { ...state, states: { ...state.states, [action.conversationId]: action.state } };
}

export interface MessagesValue {
  userId: number | null;
  conversations: Conversation[];
  loading: boolean;
  selected: Conversation | null;
  select: (conversationId: number | null) => void;
  messages: ArchivedMessage[];
  threadState: ThreadState;
  typing: number[];
  send: (text: string, replyTo: string | null) => Promise<void>;
  edit: (target: string, text: string) => Promise<void>;
  remove: (target: string) => Promise<void>;
  react: (target: string, emoji: string, on: boolean) => Promise<void>;
  notifyTyping: () => void;
  start: (request: NewConversation, name?: string) => Promise<Conversation>;
  accept: () => Promise<void>;
  decline: () => Promise<void>;
  leave: () => Promise<void>;
  block: (userId: number) => Promise<void>;
  configure: (settings: ConversationSettings) => Promise<void>;
  rename: (name: string) => Promise<void>;
  addMembers: (userIds: number[]) => Promise<void>;
  removeMember: (userId: number) => Promise<void>;
  setRole: (userId: number, role: MemberRole) => Promise<void>;
  nameOf: (conversation: Conversation) => string | null;
  verification: Messenger['verification'];
  setVerified: Messenger['setVerified'];
}

const MessagesContext = createContext<MessagesValue | null>(null);

export function useMessages(): MessagesValue {
  const value = useContext(MessagesContext);
  if (!value) throw new Error('useMessages must be used inside a MessagesProvider');
  return value;
}

interface MessagesProviderProps {
  children: ReactNode;
  messenger?: Messenger;
  initialConversationId?: number | null;
  onSelect?: (conversationId: number | null) => void;
}

export function MessagesProvider({ children, messenger: given, initialConversationId = null, onSelect }: MessagesProviderProps) {
  const { user } = useAuth();
  const userId = user?.id ?? null;
  const queryClient = useQueryClient();
  const messenger = useMemo(() => given ?? createMessenger(undefined, userId ?? undefined), [given, userId]);
  const [selectedId, setSelectedId] = useState<number | null>(initialConversationId);
  const [state, dispatch] = useReducer(threads, { messages: {}, states: {} });
  const [typingNow, setTypingNow] = useState<Record<number, Record<number, number>>>({});
  const [groupNames, setGroupNames] = useState<Record<number, string | null>>({});
  const lastTyping = useRef(0);
  const selectedRef = useRef(selectedId);
  selectedRef.current = selectedId;

  const list = useQuery({ queryKey: CONVERSATIONS_KEY, queryFn: messagesApi.list });
  const conversations = useMemo(() => list.data ?? [], [list.data]);
  const conversationsRef = useRef(conversations);
  conversationsRef.current = conversations;
  const selected = conversations.find((conversation) => conversation.id === selectedId) ?? null;

  const refreshList = useCallback(() => queryClient.invalidateQueries({ queryKey: CONVERSATIONS_KEY }), [queryClient]);

  const syncOne = useCallback(async (conversation: Conversation) => {
    try {
      const result = await messenger.sync(conversation);
      dispatch({ type: 'merge', conversationId: conversation.id, items: result.items });
      dispatch({ type: 'state', conversationId: conversation.id, state: result.outcome });
      return result;
    } catch (error) {
      dispatch({ type: 'state', conversationId: conversation.id, state: isGone(error) ? 'removed' : 'failed' });
      return null;
    }
  }, [messenger]);

  const markRead = useCallback((conversation: Conversation) => {
    if (conversation.lastSeq > conversation.me.readSeq && document.visibilityState !== 'hidden') {
      messagesApi.markRead(conversation.id, conversation.lastSeq).then(refreshList, () => undefined);
    }
  }, [refreshList]);

  const loaded = useRef(new Set<number>());
  const selectedKey = selected ? `${selected.id}:${selected.epoch}:${selected.lastSeq}` : null;

  useEffect(() => {
    const conversation = conversationsRef.current.find((each) => each.id === selectedRef.current);
    if (!conversation) return undefined;
    let current = true;
    if (!loaded.current.has(conversation.id)) {
      loaded.current.add(conversation.id);
      dispatch({ type: 'state', conversationId: conversation.id, state: 'loading' });
      messenger.history(conversation.id, Infinity, ARCHIVE_PAGE)
        .then((items) => current && dispatch({ type: 'merge', conversationId: conversation.id, items }))
        .catch(() => undefined);
    }
    void syncOne(conversation).then(async (result) => {
      if (!current || !result || result.outcome !== 'synced') return;
      markRead(conversation);
      const notices = await messenger.checkTrust(conversation).catch(() => []);
      if (current && notices.length) dispatch({ type: 'merge', conversationId: conversation.id, items: notices });
    });
    return () => {
      current = false;
    };
  }, [selectedKey, messenger, syncOne, markRead]);

  const handle = useCallback((event: ConversationEvent) => {
    const conversationId = event.conversationId;
    if (event.type === 'TYPING' && conversationId !== null && event.userId !== null) {
      setTypingNow((now) => ({ ...now, [conversationId]: { ...now[conversationId], [event.userId as number]: Date.now() } }));
      return;
    }
    if (event.type === 'MEMBERS') messenger.forgetDirectory(conversationId ?? undefined);
    if (event.type === 'WELCOME') {
      void messenger.acceptWelcomes().then(refreshList);
      return;
    }
    void refreshList();
  }, [messenger, refreshList]);

  useEffect(() => {
    if (userId === null) return undefined;
    void messenger.acceptWelcomes().then((joined) => { if (joined.length) void refreshList(); }).catch(() => undefined);
    return watchConversations(handle, () => {
      void refreshList();
    });
  }, [userId, messenger, handle, refreshList]);

  useEffect(() => {
    const timer = setInterval(() => {
      setTypingNow((now) => {
        const cutoff = Date.now() - TYPING_MS;
        let changed = false;
        const next: Record<number, Record<number, number>> = {};
        for (const [conversationId, users] of Object.entries(now)) {
          const kept = Object.fromEntries(Object.entries(users).filter(([, at]) => at > cutoff));
          if (Object.keys(kept).length !== Object.keys(users).length) changed = true;
          if (Object.keys(kept).length) next[Number(conversationId)] = kept;
        }
        return changed ? next : now;
      });
    }, 1000);
    return () => clearInterval(timer);
  }, []);

  const groupsKey = conversations.filter((each) => each.kind === 'GROUP').map((each) => `${each.id}:${each.epoch}`).join(',');

  useEffect(() => {
    const groups = conversationsRef.current.filter((each) => each.kind === 'GROUP').map((each) => each.id);
    if (!groups.length) return;
    messenger.groupNames(groups).then((names) => setGroupNames((known) => ({ ...known, ...names }))).catch(() => undefined);
  }, [groupsKey, messenger]);

  const sendPayload = useCallback(async (payload: Parameters<Messenger['send']>[1]) => {
    const conversation = conversationsRef.current.find((each) => each.id === selectedRef.current);
    if (!conversation) return;
    const items = await messenger.send(conversation, payload);
    dispatch({ type: 'merge', conversationId: conversation.id, items });
    void refreshList();
  }, [messenger, refreshList]);

  const select = useCallback((conversationId: number | null) => {
    setSelectedId(conversationId);
    onSelect?.(conversationId);
  }, [onSelect]);

  const current = () => conversationsRef.current.find((each) => each.id === selectedRef.current) ?? null;

  const value: MessagesValue = {
    userId,
    conversations,
    loading: list.isPending,
    selected,
    select,
    messages: selected ? state.messages[selected.id] ?? [] : [],
    threadState: selected ? state.states[selected.id] ?? 'loading' : 'loading',
    typing: selected ? Object.keys(typingNow[selected.id] ?? {}).map(Number) : [],
    nameOf: (conversation) => groupNames[conversation.id] ?? null,
    verification: messenger.verification,
    setVerified: messenger.setVerified,
    send: (text, replyTo) => sendPayload(textPayload(text, replyTo)),
    edit: (target, text) => sendPayload({ t: 'edit', id: newMessageId(), target, text }),
    remove: (target) => sendPayload({ t: 'delete', id: newMessageId(), target }),
    react: (target, emoji, on) => sendPayload({ t: 'react', id: newMessageId(), target, emoji, on }),
    notifyTyping: () => {
      const conversation = current();
      if (!conversation || Date.now() - lastTyping.current < TYPING_THROTTLE_MS) return;
      lastTyping.current = Date.now();
      messagesApi.typing(conversation.id).catch(() => undefined);
    },
    start: async (request, name) => {
      const conversation = await messagesApi.create(request);
      if (name) {
        await messenger.rename(conversation, name);
        setGroupNames((names) => ({ ...names, [conversation.id]: name }));
      }
      await refreshList();
      select(conversation.id);
      return conversation;
    },
    accept: async () => {
      const conversation = current();
      if (conversation) await messagesApi.accept(conversation.id);
      await refreshList();
    },
    decline: async () => {
      const conversation = current();
      if (!conversation || userId === null) return;
      await messagesApi.removeMember(conversation.id, userId);
      select(null);
      await refreshList();
    },
    leave: async () => {
      const conversation = current();
      if (!conversation || userId === null) return;
      await messagesApi.removeMember(conversation.id, userId);
      select(null);
      await refreshList();
    },
    block: async (blockedId) => {
      await messagesApi.block(blockedId);
      select(null);
      await refreshList();
    },
    configure: async (settings) => {
      const conversation = current();
      if (conversation) await messagesApi.update(conversation.id, settings);
      await refreshList();
    },
    rename: async (name) => {
      const conversation = current();
      if (!conversation) return;
      await messenger.rename(conversation, name);
      setGroupNames((names) => ({ ...names, [conversation.id]: name }));
      await refreshList();
    },
    addMembers: async (userIds) => {
      const conversation = current();
      if (conversation) await messagesApi.addMembers(conversation.id, userIds);
      messenger.forgetDirectory(conversation?.id);
      await refreshList();
    },
    removeMember: async (memberId) => {
      const conversation = current();
      if (conversation) await messagesApi.removeMember(conversation.id, memberId);
      messenger.forgetDirectory(conversation?.id);
      await refreshList();
    },
    setRole: async (memberId, role) => {
      const conversation = current();
      if (conversation) await messagesApi.setRole(conversation.id, memberId, role);
      await refreshList();
    },
  };

  return <MessagesContext.Provider value={value}>{children}</MessagesContext.Provider>;
}
