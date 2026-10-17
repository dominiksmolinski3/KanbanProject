import { onActiveBoard } from './activeBoard';
import { read, readDeleted, withRetries } from './http';
import { reorder } from './reorder';

const COLUMNS = '/api/columns';

export const fetchColumns = (retries = 3) => withRetries(async () => {
  const response = await fetch(onActiveBoard(COLUMNS));
  return read(response);
}, retries);

export const addColumn = async (name, wipLimit) => {
  const response = await fetch(onActiveBoard(COLUMNS), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, wipLimit: parseInt(wipLimit, 10) || 0 })
  });
  return read(response);
};

export const updateColumnWipLimit = async (columnId, wipLimit) => {
  const response = await fetch(`${COLUMNS}/${columnId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ wipLimit: parseInt(wipLimit, 10) })
  });
  return read(response);
};

export const updateColumnPosition = async (columnId, position) => {
  const response = await fetch(`${COLUMNS}/${columnId}/position/${position}`, { method: 'PATCH' });
  return read(response);
};

export const deleteColumn = async (columnId) => {
  const response = await fetch(`${COLUMNS}/${columnId}`, { method: 'DELETE' });
  return readDeleted(response);
};

export const updateColumnName = async (id, name) => {
  const response = await fetch(`${COLUMNS}/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name })
  });
  return read(response);
};

export const reorderColumns = (orderedIds) => reorder(COLUMNS, orderedIds, 'column');
