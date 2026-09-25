export const PRIORITY_LEVELS = ['urgent', 'high', 'medium', 'low'];

const PRIORITY_PATTERNS = [
  ['urgent', /^(urgent|critical|blocker|p0)$|^(priority[\s:_-]*)?(urgent|critical)([\s_-]*priority)?$/i],
  ['high', /^(high|p1)$|^(priority[\s:_-]*)?high([\s_-]*priority)?$/i],
  ['medium', /^(medium|normal|p2)$|^(priority[\s:_-]*)?(medium|normal)([\s_-]*priority)?$/i],
  ['low', /^(low|p3)$|^(priority[\s:_-]*)?low([\s_-]*priority)?$/i],
];

export function priorityOfLabel(label) {
  const text = String(label ?? '').trim();
  const match = PRIORITY_PATTERNS.find(([, pattern]) => pattern.test(text));
  return match ? match[0] : null;
}

// Priority is not a field of its own: it is read off the labels, and the highest one wins.
export function splitPriority(labels = []) {
  let priority = null;
  let priorityLabel = null;
  const rest = [];
  for (const label of labels) {
    const level = priorityOfLabel(label);
    if (level && (priority === null || PRIORITY_LEVELS.indexOf(level) < PRIORITY_LEVELS.indexOf(priority))) {
      if (priorityLabel !== null) rest.push(priorityLabel);
      priority = level;
      priorityLabel = label;
    } else {
      rest.push(label);
    }
  }
  return { priority, priorityLabel, labels: rest };
}

export const DUE_SOON_MS = 48 * 60 * 60 * 1000;

export function deadlineState(deadline, now = new Date()) {
  if (!deadline) return null;
  const due = new Date(deadline);
  if (Number.isNaN(due.getTime())) return null;
  const remaining = due.getTime() - now.getTime();
  if (remaining < 0) return 'overdue';
  if (remaining < DUE_SOON_MS) return 'soon';
  return 'later';
}

function hash(text) {
  let value = 0;
  for (const char of String(text)) {
    value = (value * 31 + char.codePointAt(0)) >>> 0;
  }
  return value;
}

const GOLDEN_ANGLE = 137.508;

export function hueOf(text) {
  return Math.round((hash(text) * GOLDEN_ANGLE) % 360);
}

export function initialsOf(name) {
  const parts = String(name ?? '').trim().split(/[\s@._-]+/).filter(Boolean);
  if (parts.length === 0) return '?';
  const first = [...parts[0]][0] ?? '';
  const second = parts.length > 1 ? [...parts[parts.length - 1]][0] ?? '' : '';
  return (first + second).toUpperCase();
}

export function readStoredLabelColors() {
  try {
    const stored = localStorage.getItem('labelColors');
    const parsed = stored ? JSON.parse(stored) : null;
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch {
    return {};
  }
}
