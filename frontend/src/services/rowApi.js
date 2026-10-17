import { onActiveBoard } from './activeBoard';
import { read, readDeleted, withRetries } from './http';
import { reorder } from './reorder';

const ROWS = '/api/rows';

export const fetchRows = (retries = 3) => withRetries(async () => {
  const response = await fetch(onActiveBoard(ROWS));
  return read(response);
}, retries);

export const addRow = async (name, wipLimit) => {
  const response = await fetch(onActiveBoard(ROWS), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, wipLimit: parseInt(wipLimit, 10) || 0 })
  });
  return read(response);
};

export const updateRowWipLimit = async (rowId, wipLimit) => {
  const response = await fetch(`${ROWS}/${rowId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ wipLimit: parseInt(wipLimit, 10) })
  });
  return read(response);
};

export const updateRowPosition = async (rowId, position) => {
  const response = await fetch(`${ROWS}/${rowId}/position/${position}`, { method: 'PATCH' });
  return read(response);
};

export const deleteRow = async (rowId) => {
  const response = await fetch(`${ROWS}/${rowId}`, { method: 'DELETE' });
  return readDeleted(response);
};

export const updateRowName = async (id, name) => {
  const response = await fetch(`${ROWS}/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name })
  });
  return read(response);
};

export const reorderRows = (orderedIds) => reorder(ROWS, orderedIds, 'swimlane');
