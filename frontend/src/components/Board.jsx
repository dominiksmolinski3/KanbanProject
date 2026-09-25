import { useKanban } from '../context/KanbanContext';
import React, { useEffect, useMemo, useState } from 'react';
import Task from './Task';
import EditableText from './EditableText';
import WipMeter from './WipMeter';
import { toast } from 'react-toastify';
import { useTranslation } from 'react-i18next';
import '../styles/components/BoardTokens.css';
import '../styles/components/Board.css';
import AddTaskForm from './AddTaskForm';
import AddRowColumnForm from './AddRowColumnForm';
import TaskSearch from './TaskSearch';
import BoardActions from './BoardActions';
import { buildBoardModel } from '../board/boardModel';
import useCollapsedLanes from '../board/useCollapsedLanes';

const DROP_TARGET = 'drop-target';

const markDropTarget = (e) => e.currentTarget.classList.add(DROP_TARGET);

const unmarkDropTarget = (e) => {
  if (!e.currentTarget.contains(e.relatedTarget)) {
    e.currentTarget.classList.remove(DROP_TARGET);
  }
};

function Board() {
  const [addContext, setAddContext] = useState({ type: null, columnId: null, rowId: null });
  const {
    columns,
    rows,
    tasks,
    loading,
    error,
    deleteRow,
    deleteColumn,
    dragAndDrop,
    updateColumnName,
    updateRowName,
    dailyFocusOnly,
    setDailyFocusOnly,
    keyboardMove,
    readOnly,
    activeBoardId,
  } = useKanban();

  const { t } = useTranslation();
  const { handleDragOver } = dragAndDrop;
  const lanes = useCollapsedLanes(activeBoardId);

  const model = useMemo(
    () => buildBoardModel({ columns, rows, tasks, dailyFocusOnly }),
    [columns, rows, tasks, dailyFocusOnly]
  );

  useEffect(() => {
    if (columns.length > 0) {
      document.documentElement.style.setProperty('--column-count', columns.length);
    }

    return () => {
      document.documentElement.style.setProperty('--column-count', 3);
    };
  }, [columns.length, rows.length]);

  useEffect(() => {
    const clearDropTargets = () => {
      document.querySelectorAll(`.${DROP_TARGET}`).forEach((node) => node.classList.remove(DROP_TARGET));
    };
    document.addEventListener('dragend', clearDropTargets);
    document.addEventListener('drop', clearDropTargets);
    return () => {
      document.removeEventListener('dragend', clearDropTargets);
      document.removeEventListener('drop', clearDropTargets);
    };
  }, []);

  // Only the first load gates the tree; a background refresh must not unmount an open task panel.
  if (loading && columns.length === 0 && rows.length === 0 && tasks.length === 0) {
    return (
      <div className="board-loading" role="status">
        <span className="loading-spinner" aria-hidden="true"></span>
        <span className="loading-text">{t('board.loading')}</span>
      </div>
    );
  }

  if (error) {
    return <div className="board-error" role="alert">{t('board.error', { message: error })}</div>;
  }

  const onBoardDragOver = (e) => {
    handleDragOver(e);

    if (e.dataTransfer.types.includes('application/column')) {
      e.preventDefault();
    }
  };

  const confirmDelete = (toastId, message, onConfirm) => {
    toast.info(
      <div className="toast-confirm">
        <p>{message}</p>
        <div className="toast-buttons">
          <button
            onClick={() => {
              onConfirm();
              toast.dismiss(toastId);
            }}
            className="confirm-button"
          >
            {t('taskActions.yes')}
          </button>
          <button
            onClick={() => toast.dismiss(toastId)}
            className="cancel-button"
          >
            {t('taskActions.no')}
          </button>
        </div>
      </div>,
      {
        toastId,
        autoClose: false,
        closeOnClick: false,
        draggable: false,
        closeButton: true,
        position: "top-center",
        className: 'confirmation-toast'
      }
    );
  };

  const handleDeleteRowClick = (rowId) => {
    if (readOnly) return;
    if (rows.length <= 1) {
      toast.error(t('row.cannotDeleteLast'));
      return;
    }
    const rowName = rows.find(r => r.id === rowId)?.name;
    confirmDelete(`delete-row-${rowId}`, t('row.deleteConfirm', { name: rowName }), () => {
      deleteRow(rowId).catch(() => {});
    });
  };

  const handleDeleteColumnClick = (columnId) => {
    if (readOnly) return;
    const columnName = columns.find(c => c.id === columnId)?.name;
    confirmDelete(`delete-column-${columnId}`, t('column.deleteConfirm', { name: columnName }), () => {
      deleteColumn(columnId).catch(() => {});
    });
  };

  const renderRowHeader = (row) => {
    const collapsed = lanes.isCollapsed(row.id);

    return (
      <td
        className={`grid-row-header wip-${row.wipState}${row.isOverLimit ? ' wip-exceeded' : ''}${collapsed ? ' lane-collapsed' : ''}`}
        draggable={!readOnly}
        onDragStart={(e) => dragAndDrop.handleDragStart(e, row.id, 'row')}
        onDragOver={(e) => dragAndDrop.handleDragOver(e)}
        onDragEnter={markDropTarget}
        onDragLeave={unmarkDropTarget}
        onDrop={(e) => dragAndDrop.handleDrop(e, null, row.id)}
        data-row-id={row.id}
      >
        <div className="row-title">
          <button
            type="button"
            className="lane-toggle"
            aria-expanded={!collapsed}
            aria-label={t(collapsed ? 'board.lane.expand' : 'board.lane.collapse', { name: row.name })}
            title={t(collapsed ? 'board.lane.expand' : 'board.lane.collapse', { name: row.name })}
            onClick={() => lanes.toggle(row.id)}
          >
            <span className="lane-chevron" aria-hidden="true" />
          </button>
          {!readOnly && <span className="row-drag-handle" aria-hidden="true">⋮⋮</span>}
          <EditableText
            id={row.id}
            text={row.name}
            onUpdate={updateRowName}
            className="row-name"
            inputClassName="row-name-input"
            type="row"
            disabled={readOnly}
          />
        </div>
        <div className="row-actions">
          <WipMeter count={row.taskCount} limit={row.wipLimit} state={row.wipState} />
          {!readOnly && (
            <button
              className="delete-row-btn icon-btn"
              title={t('row.delete')}
              aria-label={t('row.delete')}
              onClick={() => handleDeleteRowClick(row.id)}
            >
              ×
            </button>
          )}
        </div>
      </td>
    );
  };

  const renderColumnHeader = (column) => (
    <th
      key={column.id}
      className={`grid-column-header wip-${column.wipState}${column.isOverLimit ? ' wip-exceeded' : ''}`}
      style={{ '--wip-fill': `${column.wipFill}%` }}
      draggable={!readOnly}
      onDragStart={(e) => dragAndDrop.handleDragStart(e, column.id, 'column')}
      onDragOver={(e) => dragAndDrop.handleDragOver(e)}
      onDragEnter={markDropTarget}
      onDragLeave={unmarkDropTarget}
      onDrop={(e) => dragAndDrop.handleDrop(e, column.id)}
      data-column-id={column.id}
      scope="col"
    >
      <div className="column-header-inner">
        <div className="column-title">
          {!readOnly && <span className="column-drag-handle" aria-hidden="true">⋮⋮</span>}
          <EditableText
            id={column.id}
            text={column.name}
            onUpdate={updateColumnName}
            className="column-name"
            inputClassName="column-name-input"
            type="column"
            disabled={readOnly}
          />
        </div>
        <div className="column-actions">
          <WipMeter count={column.taskCount} limit={column.wipLimit} state={column.wipState} />
          {!readOnly && (
            <button
              className="delete-column-btn icon-btn"
              title={t('column.delete')}
              aria-label={t('column.delete')}
              onClick={() => handleDeleteColumnClick(column.id)}
            >
              ×
            </button>
          )}
        </div>
      </div>
      <div className={`wip-bar${column.wipLimit > 0 ? '' : ' wip-bar-empty'}`} aria-hidden="true"><span /></div>
    </th>
  );

  const renderCell = (column, row) => {
    const cellTasks = model.tasksIn(column.id, row.id);
    const collapsed = lanes.isCollapsed(row.id);
    const shouldHighlight = cellTasks.length > 0 && (column.isOverLimit || row.isOverLimit);
    const isKeyboardTarget = keyboardMove.isTarget(column.id, row.id);

    const onDragOver = (e) => {
      e.preventDefault();
      dragAndDrop.handleDragOver(e);
    };

    const onDrop = (e) => {
      e.preventDefault();
      e.currentTarget.classList.remove(DROP_TARGET);
      dragAndDrop.handleDrop(e, column.id, row.id);
    };

    return (
      <td
        key={`${row.id}-${column.id}`}
        className={`grid-cell${shouldHighlight ? ' wip-exceeded-cell' : ''}${isKeyboardTarget ? ' keyboard-move-target' : ''}${collapsed ? ' lane-collapsed-cell' : ''}`}
        data-column-id={column.id}
        data-row-id={row.id}
        data-keyboard-target={isKeyboardTarget ? 'true' : undefined}
        onDragOver={onDragOver}
        onDragEnter={markDropTarget}
        onDragLeave={unmarkDropTarget}
        onDrop={onDrop}
      >
        {collapsed ? (
          cellTasks.length > 0 && (
            <span className="lane-hidden-count">{t('board.lane.hidden', { n: cellTasks.length })}</span>
          )
        ) : (
          <div className="cell-stack">
            {cellTasks.map(task => (
              <Task
                key={task.id}
                task={task}
                columnId={column.id}
                rowId={row.id}
              />
            ))}
            {!readOnly && (
              <button
                className="add-task-placeholder"
                onClick={() => setAddContext({ type: 'task', columnId: column.id, rowId: row.id })}
                title={t('taskActions.addTaskHere')}
                aria-label={t('taskActions.addTaskHere')}
              >
                {t('taskActions.addTaskHere')}
              </button>
            )}
          </div>
        )}
      </td>
    );
  };

  const announcement = keyboardMove.announcement;
  const closeForm = () => setAddContext({ type: null, columnId: null, rowId: null });

  return (
    <div className="board-grid" onDragOver={onBoardDragOver}>
      <p id="board-keyboard-move-help" className="visually-hidden">
        {t('board.keyboardMove.help')}
      </p>
      <div className="visually-hidden" role="status" aria-live="polite" aria-atomic="true">
        {announcement ? t(announcement.key, announcement.values) : ''}
      </div>
      {readOnly && (
        <div className="board-readonly-banner" role="status">
          {t('board.readOnlyBanner')}
        </div>
      )}
      <div className="board-toolbar">
        {!readOnly && <BoardActions />}
        <button
          type="button"
          className={`daily-focus-filter ${dailyFocusOnly ? 'active' : ''}`}
          aria-pressed={dailyFocusOnly}
          onClick={() => setDailyFocusOnly(!dailyFocusOnly)}
        >
          <span aria-hidden="true">★</span> {t('board.dailyFocus')}
          <span className="daily-focus-count">{model.dailyFocusCount}</span>
        </button>
        {dailyFocusOnly && model.dailyFocusCount === 0 && (
          <span className="daily-focus-empty">{t('board.dailyFocusEmpty')}</span>
        )}
        <TaskSearch />
      </div>
      <div className="board-scroller">
        <table className="kanban-table">
          <thead>
            <tr>
              <th className="grid-corner" aria-hidden="true"></th>

              {model.columns.map(column => renderColumnHeader(column))}
              {!readOnly && (
                <th className="grid-column-header add-placeholder-header">
                  <button
                    className="add-column-btn"
                    title={t('column.add')}
                    onClick={() => setAddContext({ type: 'column', columnId: null, rowId: null })}
                  >
                    <span aria-hidden="true">+</span> {t('column.add')}
                  </button>
                </th>
              )}
            </tr>
          </thead>
          <tbody>
            {model.rows.map(row => (
              <tr key={row.id} className={lanes.isCollapsed(row.id) ? 'lane-row collapsed' : 'lane-row'}>
                {renderRowHeader(row)}

                {model.columns.map(column => renderCell(column, row))}
                <td className="grid-cell grid-filler" />
              </tr>
            ))}
            {!readOnly && (
              <tr>
                <td className="grid-row-header add-placeholder-row">
                  <button
                    className="add-row-btn"
                    title={t('row.add')}
                    onClick={() => setAddContext({ type: 'row', columnId: null, rowId: null })}
                  >
                    <span aria-hidden="true">+</span> {t('row.add')}
                  </button>
                </td>
                {model.columns.map((column) => (
                  <td key={`add-row-empty-${column.id}`} className="grid-cell grid-filler"/>
                ))}
                <td className="grid-cell grid-filler" />
              </tr>
            )}
          </tbody>
        </table>
      </div>
      {addContext.type === 'task' && (
        <AddTaskForm
          onClose={closeForm}
          defaultColumnId={addContext.columnId || ''}
          defaultRowId={addContext.rowId || ''}
        />
      )}
      {addContext.type === 'column' && (
        <AddRowColumnForm onClose={closeForm} defaultTab="column" />
      )}
      {addContext.type === 'row' && (
        <AddRowColumnForm onClose={closeForm} defaultTab="row" />
      )}
    </div>
  );
}

export default Board;
