import type { Task } from '../../types/api';

export type LinkRole = 'child' | 'parent' | null;

export function linkRoleOf(task: Task, tasks: Task[], linkFocusId: string | number | null): LinkRole {
  const focusTask = linkFocusId !== null && String(linkFocusId) !== String(task.id)
    ? tasks.find((candidate) => String(candidate.id) === String(linkFocusId))
    : null;
  if (focusTask && (focusTask.childTaskIds || []).some((id) => String(id) === String(task.id))) {
    return 'child';
  }
  if (focusTask && String(focusTask.parentTaskId) === String(task.id)) {
    return 'parent';
  }
  return null;
}
