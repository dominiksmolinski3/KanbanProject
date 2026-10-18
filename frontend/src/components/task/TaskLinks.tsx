import Xarrow from 'react-xarrows';
import type { Task } from '../../types/api';

const linkColor = (token: string) => getComputedStyle(document.documentElement).getPropertyValue(token).trim() || 'currentColor';

const isOnBoard = (id: number) => Boolean(document.getElementById(`task-${id}`));

export default function TaskLinks({ task }: { task: Task }) {
  const childTaskIds = task.childTaskIds || [];
  const parentTaskId = task.parentTaskId ?? null;

  return (
    <>
      {childTaskIds.filter(isOnBoard).map(childId => (
        <Xarrow
          key={`arrow-${task.id}-${childId}`}
          start={`task-${task.id}`}
          end={`task-${childId}`}
          color={linkColor('--kb-accent')}
          strokeWidth={2}
          path="smooth"
          curveness={0.45}
          zIndex={20}
          showHead
          headSize={5}
          showTail
          tailShape="circle"
          tailSize={3}
        />
      ))}

      {parentTaskId !== null && isOnBoard(parentTaskId) && (
        <Xarrow
          key={`arrow-${task.id}-${parentTaskId}`}
          start={`task-${task.id}`}
          end={`task-${parentTaskId}`}
          color={linkColor('--kb-ink-muted')}
          strokeWidth={2}
          path="smooth"
          curveness={0.45}
          dashness={{ strokeLen: 6, nonStrokeLen: 5 }}
          zIndex={20}
          showHead
          headSize={5}
          showTail
          tailShape="circle"
          tailSize={3}
        />
      )}
    </>
  );
}
