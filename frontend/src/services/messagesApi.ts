import { deleteChecked, getJson, patchJson, postJson, postVoid, putJson, queryString } from './http';
import type { DirectoryMember, ServerMessage } from '../crypto/engine';
import type { KeyLogEntry } from '../crypto/keyLog';

const API_BASE_URL = '/api';
const CONVERSATIONS = `${API_BASE_URL}/conversations`;

export type ConversationKind = 'DIRECT' | 'GROUP';
export type HistoryVisibility = 'JOINED' | 'DAYS_30' | 'ALL';
export type WhoCanAdd = 'ADMINS' | 'MEMBERS';
export type MemberRole = 'ADMIN' | 'MEMBER';
export type MemberState = 'ACTIVE' | 'REQUEST';

export interface ConversationMemberInfo {
  userId: number;
  name: string;
  hasAvatar: boolean;
  role: MemberRole;
  state: MemberState;
  joinedAt: string;
  joinedSeq: number;
  deliveredSeq: number;
  readSeq: number;
}

export interface Membership {
  role: MemberRole;
  state: MemberState;
  joinedSeq: number;
  deliveredSeq: number;
  readSeq: number;
  muted: boolean;
  pinned: boolean;
  archived: boolean;
}

export interface Conversation {
  id: number;
  kind: ConversationKind;
  groupId: string;
  epoch: number;
  needsCommit: boolean;
  historyVisibility: HistoryVisibility;
  whoCanAdd: WhoCanAdd;
  lastSeq: number;
  createdAt: string;
  lastActivityAt: string;
  unread: number;
  me: Membership;
  members: ConversationMemberInfo[];
}

export interface Contact {
  id: number;
  name: string;
  email: string | null;
  hasAvatar: boolean;
  messaging: boolean;
}

export interface BlockedPerson {
  userId: number;
  name: string;
  createdAt: string;
}

export interface Posted {
  seq: number;
  epoch: number;
  createdAt: string;
}

export interface Welcome {
  id: number;
  conversationId: number;
  seq: number;
  body: string;
}

export interface ClaimedKeyPackage {
  deviceId: number;
  userId: number;
  keyPackage: string;
}

export interface ConversationSettings {
  muted?: boolean;
  pinned?: boolean;
  archived?: boolean;
  historyVisibility?: HistoryVisibility;
  whoCanAdd?: WhoCanAdd;
}

export interface NewConversation {
  kind: ConversationKind;
  userIds: number[];
  historyVisibility?: HistoryVisibility;
  whoCanAdd?: WhoCanAdd;
}

export const MESSAGE_PAGE = 100;

const conversation = (id: number) => `${CONVERSATIONS}/${id}`;

export const messagesApi = {
  list: () => getJson<Conversation[]>(CONVERSATIONS),

  get: (id: number) => getJson<Conversation>(conversation(id)),

  create: (request: NewConversation) => postJson<Conversation>(CONVERSATIONS, request),

  contacts: () => getJson<Contact[]>(`${CONVERSATIONS}/contacts`),

  lookup: (email: string) => getJson<Contact>(`${CONVERSATIONS}/lookup?${queryString({ email })}`),

  update: (id: number, settings: ConversationSettings) => patchJson<Conversation>(conversation(id), settings),

  addMembers: (id: number, userIds: number[]) =>
    postJson<Conversation>(`${CONVERSATIONS}/${id}/members`, { userIds }),

  setRole: (id: number, userId: number, role: MemberRole) =>
    putJson<Conversation>(`${CONVERSATIONS}/${id}/members/${userId}`, { role }),

  removeMember: (id: number, userId: number) => deleteChecked(`${CONVERSATIONS}/${id}/members/${userId}`),

  accept: (id: number) => postJson<Conversation>(`${CONVERSATIONS}/${id}/accept`),

  markDelivered: (id: number, seq: number) => postVoid(`${CONVERSATIONS}/${id}/delivered`, { seq }),

  markRead: (id: number, seq: number) => postVoid(`${CONVERSATIONS}/${id}/read`, { seq }),

  typing: (id: number) => postVoid(`${CONVERSATIONS}/${id}/typing`),

  directory: (id: number) => getJson<DirectoryMember[]>(`${CONVERSATIONS}/${id}/devices`),

  claim: (id: number, deviceIds: number[]) =>
    postJson<ClaimedKeyPackage[]>(`${CONVERSATIONS}/${id}/key-packages`, { deviceIds }),

  commit: (id: number, body: { deviceId: number; epoch: number; body: string; welcome: string | null;
    welcomeDeviceIds: number[] }) => postJson<Posted>(`${CONVERSATIONS}/${id}/commits`, body),

  post: (id: number, body: { deviceId: number; epoch: number; body: string }) =>
    postJson<Posted>(`${CONVERSATIONS}/${id}/messages`, body),

  messages: (id: number, after: number, size = MESSAGE_PAGE) =>
    getJson<ServerMessage[]>(`${CONVERSATIONS}/${id}/messages?${queryString({ after, size })}`),

  welcomes: (deviceId: number) => getJson<Welcome[]>(`${API_BASE_URL}/crypto/devices/${deviceId}/welcomes`),

  dropWelcome: (deviceId: number, welcomeId: number) =>
    deleteChecked(`${API_BASE_URL}/crypto/devices/${deviceId}/welcomes/${welcomeId}`),

  keyLog: (userId: number, after: number) =>
    getJson<KeyLogEntry[]>(`${API_BASE_URL}/crypto/users/${userId}/key-log?${queryString({ after })}`),

  blocks: () => getJson<BlockedPerson[]>(`${API_BASE_URL}/blocks`),

  block: (userId: number) => postVoid(`${API_BASE_URL}/blocks`, { userId }),

  unblock: (userId: number) => deleteChecked(`${API_BASE_URL}/blocks/${userId}`),
};
