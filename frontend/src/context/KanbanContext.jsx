/* eslint-disable react-refresh/only-export-components */
import React, { createContext, useState, useContext } from 'react';
import { useTranslation } from 'react-i18next';
import { useKeyboardMove } from './keyboardMove';
import { readOnlyGuard } from './kanban/readOnlyGuard';
import { useBoards } from './kanban/useBoards';
import { useBoardData } from './kanban/useBoardData';
import { useLayoutActions } from './kanban/useLayoutActions';
import { useTaskActions } from './kanban/useTaskActions';
import { useDragAndDrop } from './kanban/useDragAndDrop';

const KanbanContext = createContext();

export function KanbanProvider({ children }) {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [dailyFocusOnly, setDailyFocusOnly] = useState(false);
  const { t } = useTranslation();

  const boards = useBoards({ t, setError, setLoading });
  const data = useBoardData({ activeBoardId: boards.activeBoardId, setLoading, setError });
  const guard = readOnlyGuard(boards.isViewer, t);
  const shared = { t, ...guard, ...data };
  const layout = useLayoutActions(shared);
  const taskActions = useTaskActions(shared);
  const dragAndDrop = useDragAndDrop({
    moveTask: taskActions.moveTask,
    moveColumn: layout.moveColumn,
    moveRow: layout.moveRow,
    reorderTask: taskActions.reorderTask,
  });

  const keyboardMoveRaw = useKeyboardMove({ columns: data.columns, rows: data.rows, moveTask: taskActions.moveTask });
  const [draggedTaskId, setDraggedTaskId] = useState(null);
  const linkFocusId = draggedTaskId ?? keyboardMoveRaw.held?.taskId ?? null;

  const keyboardMove = {
    ...keyboardMoveRaw,
    grab: (...args) => {
      if (boards.isViewer) return;
      keyboardMoveRaw.grab(...args);
    }
  };

  const value = {
    boards: boards.boards,
    activeBoard: boards.activeBoard,
    activeBoardId: boards.activeBoardId,
    readOnly: boards.isViewer,
    selectBoard: boards.selectBoard,
    refreshBoards: boards.refreshBoards,
    createBoard: boards.createBoard,
    renameBoard: boards.renameBoard,
    deleteBoard: boards.deleteBoard,
    inviteToBoard: boards.inviteToBoard,
    myInvitations: boards.myInvitations,
    refreshMyInvitations: boards.refreshMyInvitations,
    revokeInvitation: boards.revokeInvitation,
    acceptInvitation: boards.acceptInvitation,
    declineInvitation: boards.declineInvitation,
    removeBoardMember: boards.removeBoardMember,
    columns: data.columns,
    tasks: data.tasks,
    rows: data.rows,
    loading,
    error,
    columnMap: data.columnMap,
    addTask: taskActions.addTask,
    addColumn: layout.addColumn,
    addRow: layout.addRow,
    updateWipLimit: layout.updateWipLimit,
    updateRowWipLimit: layout.updateRowWipLimit,
    deleteColumn: layout.deleteColumn,
    deleteRow: layout.deleteRow,
    deleteTask: taskActions.deleteTask,
    moveTask: taskActions.moveTask,
    moveColumn: layout.moveColumn,
    moveRow: layout.moveRow,
    refreshTasks: data.refreshTasks,
    refreshBoard: data.refreshBoard,
    updateTaskName: taskActions.updateTaskName,
    updateColumnName: layout.updateColumnName,
    updateRowName: layout.updateRowName,
    getUserWipStatus: taskActions.getUserWipStatus,
    updateUserWipLimit: taskActions.updateUserWipLimit,
    updateTaskCompletion: taskActions.updateTaskCompletion,
    setDailyFocus: taskActions.setDailyFocus,
    dailyFocusOnly,
    setDailyFocusOnly,
    dragAndDrop,
    keyboardMove,
    linkFocusId,
    setDraggedTaskId
  };

  return <KanbanContext.Provider value={value}>{children}</KanbanContext.Provider>;
}

export const useKanban = () => {
  const context = useContext(KanbanContext);
  if (!context) {
    throw new Error('useKanban must be used within a KanbanProvider');
  }
  return context;
};

export default KanbanContext;
