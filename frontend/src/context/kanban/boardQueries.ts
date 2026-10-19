import type { Id } from '../../types/api';

export const boardKeys = {
  columns: (boardId: Id | null) => ['board', boardId, 'columns'] as const,
  rows: (boardId: Id | null) => ['board', boardId, 'rows'] as const,
  tasks: (boardId: Id | null) => ['board', boardId, 'tasks'] as const,
};

export const byPosition = <T extends { position: number }>(items: T[]): T[] =>
  [...items].sort((a, b) => a.position - b.position);
