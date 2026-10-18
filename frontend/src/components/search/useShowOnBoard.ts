import { useState } from 'react';
import type { Id } from '../../types/api';

const HIGHLIGHT_MS = 2000;

function showOnBoard(taskId: Id): boolean {
  const card = document.getElementById(`task-${taskId}`);
  if (!card) {
    return false;
  }
  card.scrollIntoView({ behavior: 'smooth', block: 'center' });
  card.classList.add('task-search-hit');
  setTimeout(() => card.classList.remove('task-search-hit'), HIGHLIGHT_MS);
  return true;
}

export function useShowOnBoard() {
  const [missing, setMissing] = useState<Id | null>(null);

  return {
    missing,
    show: (taskId: Id) => setMissing(showOnBoard(taskId) ? null : taskId),
  };
}
