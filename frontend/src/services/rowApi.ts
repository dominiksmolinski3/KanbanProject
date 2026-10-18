import { onActiveBoard } from './activeBoard';
import { deleteQuietly, getJson, patchJson, postJson, withRetries } from './http';
import { reorder } from './reorder';
import type { Id, Row, RowSummary } from '../types/api';

const ROWS = '/api/rows';

export const fetchRows = (retries = 3) => withRetries(() => getJson<Row[]>(onActiveBoard(ROWS)), retries);

export const addRow = async (name: string, wipLimit: number | string) =>
  postJson<RowSummary>(onActiveBoard(ROWS), { name, wipLimit: parseInt(String(wipLimit), 10) || 0 });

export const updateRowWipLimit = (rowId: Id, wipLimit: number | string) =>
  patchJson<Row>(`${ROWS}/${rowId}`, { wipLimit: parseInt(String(wipLimit), 10) });

export const updateRowPosition = (rowId: Id, position: number) =>
  patchJson<Row>(`${ROWS}/${rowId}/position/${position}`);

export const deleteRow = (rowId: Id) => deleteQuietly(`${ROWS}/${rowId}`);

export const updateRowName = (id: Id, name: string) => patchJson<Row>(`${ROWS}/${id}`, { name });

export const reorderRows = (orderedIds: Id[]) => reorder<Row[]>(ROWS, orderedIds, 'swimlane');
