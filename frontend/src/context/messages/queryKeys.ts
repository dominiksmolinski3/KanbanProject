export const MESSAGES_KEY = ['messages'] as const;
export const CONVERSATIONS_KEY = [...MESSAGES_KEY, 'conversations'] as const;
export const CONTACTS_KEY = [...MESSAGES_KEY, 'contacts'] as const;
