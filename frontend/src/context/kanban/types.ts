import type { Dispatch, SetStateAction } from 'react';
import type { TFunction } from 'i18next';
import type { ColumnSummary, Id, RowSummary, Task } from '../../types/api';

export type Translate = TFunction;

export type SetState<T> = Dispatch<SetStateAction<T>>;

// Ids that come back from the DOM (dataTransfer) are strings, and the board has always passed them on as they are.
export type LooseId = Id | string;

export type MoveTask = (taskId: LooseId, columnId?: LooseId | null, rowId?: LooseId | null) => Promise<void>;

export interface ReadOnlyGuard {
  blockIfReadOnly: () => boolean;
  refuseIfReadOnly: () => void;
}

export interface BoardData {
  columns: ColumnSummary[];
  setColumns: SetState<ColumnSummary[]>;
  tasks: Task[];
  setTasks: SetState<Task[]>;
  rows: RowSummary[];
  setRows: SetState<RowSummary[]>;
  refreshTasks: () => Promise<void>;
  refreshBoard: () => Promise<void>;
}

export type SharedActionDeps = { t: Translate } & ReadOnlyGuard & BoardData;
