import { useState, type DragEvent } from 'react';
import { useTranslation } from 'react-i18next';
import type { Id, TaskAttachment } from '../../types/api';
import ConfirmDialog from './ConfirmDialog';
import AttachmentPicker from './attachments/AttachmentPicker';
import AttachmentRow from './attachments/AttachmentRow';
import { useAttachments } from './attachments/useAttachments';

function TaskAttachments({ taskId, readOnly }: { taskId: Id; readOnly: boolean }) {
  const { t } = useTranslation();
  const { attachments, loaded, uploading, upload, download, remove } = useAttachments(taskId);
  const [toDelete, setToDelete] = useState<TaskAttachment | null>(null);
  const [draggingFileOver, setDraggingFileOver] = useState(false);

  const handleDragOver = (event: DragEvent) => {
    event.preventDefault();
    setDraggingFileOver(true);
  };

  const handleDrop = (event: DragEvent) => {
    event.preventDefault();
    event.stopPropagation();
    setDraggingFileOver(false);
    upload(event.dataTransfer?.files);
  };

  const confirmDelete = async () => {
    if (!toDelete) {
      return;
    }
    try {
      await remove(toDelete);
    } finally {
      setToDelete(null);
    }
  };

  return (
    <div
      className={`attachments-section${draggingFileOver ? ' drop-target' : ''}`}
      onDragOver={readOnly ? undefined : handleDragOver}
      onDragLeave={readOnly ? undefined : () => setDraggingFileOver(false)}
      onDrop={readOnly ? undefined : handleDrop}
    >
      <div className="section-header">
        <h3>{t('taskActions.attachments')}</h3>
        {attachments.length > 0 && <span className="section-meta">{attachments.length}</span>}
      </div>

      {!readOnly && <AttachmentPicker uploading={uploading} onFiles={upload} />}

      {attachments.length > 0 ? (
        <div className="attachments-list">
          {attachments.map(attachment => (
            <AttachmentRow
              key={attachment.id}
              attachment={attachment}
              readOnly={readOnly}
              onDownload={() => download(attachment)}
              onAskDelete={() => setToDelete(attachment)}
            />
          ))}
        </div>
      ) : loaded && (
        <p className="no-attachments">{t('taskActions.noAttachments')}</p>
      )}

      {toDelete && (
        <ConfirmDialog
          title={t('taskActions.confirmDeleteAttachment')}
          subject={toDelete.fileName}
          onConfirm={confirmDelete}
          onCancel={() => setToDelete(null)}
        />
      )}
    </div>
  );
}

export default TaskAttachments;
