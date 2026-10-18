import type { KeyboardEvent } from 'react';
import type { KanbanValue } from '../../context/KanbanContext';
import type { Id, Task } from '../../types/api';

const ARROWS = { ArrowLeft: 'left', ArrowRight: 'right', ArrowUp: 'up', ArrowDown: 'down' } as const;

interface GripContext {
  task: Task;
  columnId: Id;
  rowId?: Id | null;
  held: boolean;
  readOnly: boolean;
  keyboardMove: KanbanValue['keyboardMove'];
  openDetails: () => void;
}

export function handleGripKey(e: KeyboardEvent, { task, columnId, rowId, held, readOnly, keyboardMove, openDetails }: GripContext) {
  if (held) {
    const direction = ARROWS[e.key as keyof typeof ARROWS];
    if (direction) {
      e.preventDefault();
      keyboardMove.step(direction);
      return;
    }
    if (e.key === ' ' || e.key === 'Enter') {
      e.preventDefault();
      keyboardMove.drop();
      return;
    }
    if (e.key === 'Escape') {
      e.preventDefault();
      keyboardMove.cancel();
    }
    return;
  }
  if (e.key === ' ') {
    if (readOnly) {
      return;
    }
    e.preventDefault();
    keyboardMove.grab(task, columnId, rowId ?? task.rowId);
    return;
  }
  if (e.key === 'Enter') {
    e.preventDefault();
    openDetails();
  }
}
