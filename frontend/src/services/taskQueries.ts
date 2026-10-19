import type { Id } from '../types/api';

export const taskKeys = {
  comments: (taskId: Id) => ['comments', taskId] as const,
  subtasks: (taskId: Id) => ['subtasks', taskId] as const,
  attachments: (taskId: Id) => ['attachments', taskId] as const,
  history: (taskId: Id) => ['history', taskId] as const,
  timeSpent: (taskId: Id) => ['timeSpent', taskId] as const,
  anyComments: ['comments'] as const,
  anySubtasks: ['subtasks'] as const,
  anyAttachments: ['attachments'] as const,
};
