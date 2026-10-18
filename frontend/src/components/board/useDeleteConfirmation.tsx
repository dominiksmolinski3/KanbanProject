import { toast } from 'react-toastify';
import { useTranslation } from 'react-i18next';
import { useKanban } from '../../context/KanbanContext';
import type { Id } from '../../types/api';

export function useDeleteConfirmation() {
  const { t } = useTranslation();
  const { columns, rows, readOnly, deleteRow, deleteColumn } = useKanban();

  const confirmDelete = (toastId: string, message: string, onConfirm: () => void) => {
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

  const deleteRowClick = (rowId: Id) => {
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

  const deleteColumnClick = (columnId: Id) => {
    if (readOnly) return;
    const columnName = columns.find(c => c.id === columnId)?.name;
    confirmDelete(`delete-column-${columnId}`, t('column.deleteConfirm', { name: columnName }), () => {
      deleteColumn(columnId).catch(() => {});
    });
  };

  return { deleteRowClick, deleteColumnClick };
}
