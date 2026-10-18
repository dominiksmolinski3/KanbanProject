import { onActiveBoard } from './activeBoard';
import { deleteQuietly, getJson, patchJson, postJson, withRetries } from './http';
import { reorder } from './reorder';
import type { Column, ColumnSummary, Id } from '../types/api';

const COLUMNS = '/api/columns';

export const fetchColumns = (retries = 3) => withRetries(() => getJson<Column[]>(onActiveBoard(COLUMNS)), retries);

export const addColumn = async (name: string, wipLimit: number | string) =>
  postJson<ColumnSummary>(onActiveBoard(COLUMNS), { name, wipLimit: parseInt(String(wipLimit), 10) || 0 });

export const updateColumnWipLimit = (columnId: Id, wipLimit: number | string) =>
  patchJson<Column>(`${COLUMNS}/${columnId}`, { wipLimit: parseInt(String(wipLimit), 10) });

export const updateColumnPosition = (columnId: Id, position: number) =>
  patchJson<Column>(`${COLUMNS}/${columnId}/position/${position}`);

export const deleteColumn = (columnId: Id) => deleteQuietly(`${COLUMNS}/${columnId}`);

export const updateColumnName = (id: Id, name: string) => patchJson<Column>(`${COLUMNS}/${id}`, { name });

export const reorderColumns = (orderedIds: Id[]) => reorder<Column[]>(COLUMNS, orderedIds, 'column');
