import type { TFunction } from 'i18next';
import type { Conversation } from '../../services/messagesApi';

export function others(conversation: Conversation, userId: number | null) {
  return conversation.members.filter((member) => member.userId !== userId);
}

export function conversationTitle(conversation: Conversation, userId: number | null, groupName: string | null,
  t: TFunction, language: string): string {
  const rest = others(conversation, userId);
  if (conversation.kind === 'BOARD') {
    return conversation.boardName ?? t('messages.board.untitled');
  }
  if (conversation.kind === 'DIRECT') {
    return rest[0]?.name ?? t('messages.unknownPerson');
  }
  if (groupName) return groupName;
  if (!rest.length) return t('messages.untitledGroup');
  return new Intl.ListFormat(language, { style: 'short', type: 'conjunction' }).format(rest.map((member) => member.name));
}

export const initials = (name: string): string =>
  name.split(/\s+/).filter(Boolean).slice(0, 2).map((part) => part[0]?.toUpperCase() ?? '').join('') || '?';
