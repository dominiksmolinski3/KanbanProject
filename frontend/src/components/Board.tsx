import { useMemo, useState, type DragEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useKanban } from '../context/KanbanContext';
import { buildBoardModel } from '../board/boardModel';
import useCollapsedLanes from '../board/useCollapsedLanes';
import useNarrowScreen from '../board/useNarrowScreen';
import AddRowColumnForm from './AddRowColumnForm';
import AddTaskForm from './AddTaskForm';
import Icon from './Icon';
import BoardGrid, { type AddRequest } from './board/BoardGrid';
import BoardToolbar from './board/BoardToolbar';
import KeyboardMoveStrip from './board/KeyboardMoveStrip';
import PhoneColumnTabs from './board/PhoneColumnTabs';
import { useClearDropTargets } from './board/dropTarget';
import { useColumnCountVariable } from './board/useColumnCountVariable';
import '../styles/components/Board.css';

function Board() {
  const [addRequest, setAddRequest] = useState<AddRequest | null>(null);
  const [phoneColumnId, setPhoneColumnId] = useState<number | null>(null);
  const {
    columns,
    rows,
    tasks,
    loading,
    error,
    dragAndDrop,
    dailyFocusOnly,
    keyboardMove,
    readOnly,
    activeBoardId,
  } = useKanban();

  const { t } = useTranslation();
  const lanes = useCollapsedLanes(activeBoardId);
  const narrow = useNarrowScreen();

  const model = useMemo(
    () => buildBoardModel({ columns, rows, tasks, dailyFocusOnly }),
    [columns, rows, tasks, dailyFocusOnly]
  );

  useColumnCountVariable(columns.length);
  useClearDropTargets();

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

  const onBoardDragOver = (e: DragEvent<HTMLDivElement>) => {
    dragAndDrop.handleDragOver(e);

    if (e.dataTransfer.types.includes('application/column')) {
      e.preventDefault();
    }
  };

  const phoneColumn = model.columns.find((column) => column.id === phoneColumnId) || model.columns[0];
  const announcement = keyboardMove.announcement;
  const announced = announcement ? t(announcement.key, announcement.values) : '';
  const closeForm = () => setAddRequest(null);

  return (
    <div className="board-grid" onDragOver={onBoardDragOver}>
      <p id="board-keyboard-move-help" className="visually-hidden">
        {t('board.keyboardMove.help')}
      </p>
      <div className="visually-hidden" role="status" aria-live="polite" aria-atomic="true">
        {announced}
      </div>
      {readOnly && (
        <div className="board-readonly-banner" role="status">
          <Icon name="eye" size="sm" />
          {t('board.readOnlyBanner')}
        </div>
      )}
      <BoardToolbar dailyFocusCount={model.dailyFocusCount} />
      {narrow && model.columns.length > 0 && (
        <PhoneColumnTabs columns={model.columns} activeId={phoneColumn?.id} onSelect={setPhoneColumnId} />
      )}
      <BoardGrid
        model={model}
        phoneColumnId={phoneColumn?.id}
        isCollapsed={lanes.isCollapsed}
        toggleLane={lanes.toggle}
        onAdd={setAddRequest}
      />
      {keyboardMove.held && <KeyboardMoveStrip message={announced} />}
      {addRequest?.type === 'task' && (
        <AddTaskForm onClose={closeForm} defaultColumnId={addRequest.columnId || ''} defaultRowId={addRequest.rowId || ''} />
      )}
      {addRequest?.type === 'column' && (
        <AddRowColumnForm onClose={closeForm} defaultTab="column" />
      )}
      {addRequest?.type === 'row' && (
        <AddRowColumnForm onClose={closeForm} defaultTab="row" />
      )}
    </div>
  );
}

export default Board;
