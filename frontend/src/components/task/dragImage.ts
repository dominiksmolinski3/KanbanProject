import type { DragEvent } from 'react';

const TILT_GHOST_CLASS = 'task-drag-ghost';

export function setTiltedDragImage(e: DragEvent<Element>, source: HTMLElement | null) {
  if (!source || typeof e.dataTransfer.setDragImage !== 'function') return;
  const rect = source.getBoundingClientRect();
  const ghost = source.cloneNode(true) as HTMLElement;
  ghost.removeAttribute('id');
  ghost.classList.add(TILT_GHOST_CLASS);
  ghost.style.width = `${rect.width}px`;
  document.body.appendChild(ghost);
  e.dataTransfer.setDragImage(ghost, e.clientX - rect.left, e.clientY - rect.top);
  // The browser snapshots the ghost during dragstart, so it can leave the DOM on the next frame.
  requestAnimationFrame(() => ghost.remove());
}
