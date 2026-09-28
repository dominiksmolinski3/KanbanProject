import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { toast } from 'react-toastify';
import { AttachmentUploadError, MAX_ATTACHMENT_SIZE, deleteTaskAttachment, downloadTaskAttachment, fetchTaskAttachments, uploadTaskAttachment } from '../../services/api';
import ConfirmDialog from './ConfirmDialog';

const formatFileSize = (bytes) => {
  if (!Number.isFinite(bytes) || bytes < 0) {
    return '';
  }
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  const units = ['KB', 'MB', 'GB'];
  let size = bytes / 1024;
  let unit = 0;
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024;
    unit += 1;
  }
  return `${size < 10 ? size.toFixed(1) : Math.round(size)} ${units[unit]}`;
};

function TaskAttachments({ taskId, readOnly }) {
  const { t } = useTranslation();
  const [attachments, setAttachments] = useState([]);
  const [loaded, setLoaded] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [attachmentToDelete, setAttachmentToDelete] = useState(null);
  const [draggingFileOver, setDraggingFileOver] = useState(false);
  const inputRef = useRef(null);

  const loadAttachments = useCallback(async () => {
    try {
      const files = await fetchTaskAttachments(taskId);
      setAttachments(Array.isArray(files) ? files : []);
    } catch (error) {
      console.error('Error fetching task attachments:', error);
      setAttachments([]);
    } finally {
      setLoaded(true);
    }
  }, [taskId]);

  useEffect(() => {
    loadAttachments();
    window.addEventListener('task-attachments-changed', loadAttachments);
    return () => window.removeEventListener('task-attachments-changed', loadAttachments);
  }, [loadAttachments]);

  const reportUploadError = (error, fileName) => {
    if (error instanceof AttachmentUploadError && error.reason === 'tooLarge') {
      toast.error(t('taskActions.attachmentTooLarge', {
        name: fileName,
        size: formatFileSize(MAX_ATTACHMENT_SIZE)
      }));
      return;
    }
    if (error instanceof AttachmentUploadError && error.reason === 'storageUnavailable') {
      toast.error(t('taskActions.attachmentStorageUnavailable'));
      return;
    }
    console.error('Error uploading attachment:', error);
    toast.error(t('taskActions.attachmentUploadFailed', { name: fileName }));
  };

  const upload = async (files) => {
    const chosen = Array.from(files || []);
    if (chosen.length === 0) {
      return;
    }

    setUploading(true);
    try {
      for (const file of chosen) {
        try {
          await uploadTaskAttachment(taskId, file);
        } catch (error) {
          reportUploadError(error, file.name);
        }
      }
      await loadAttachments();
    } finally {
      setUploading(false);
    }
  };

  const handleInputChange = (event) => {
    upload(event.target.files);
    // Reset so picking the same file again still fires a change event.
    event.target.value = '';
  };

  const handleDragOver = (event) => {
    event.preventDefault();
    setDraggingFileOver(true);
  };

  const handleDrop = (event) => {
    event.preventDefault();
    event.stopPropagation();
    setDraggingFileOver(false);
    upload(event.dataTransfer?.files);
  };

  const handleDownload = async (attachment) => {
    try {
      await downloadTaskAttachment(taskId, attachment.id, attachment.fileName);
    } catch (error) {
      console.error('Error downloading attachment:', error);
      toast.error(t('taskActions.attachmentDownloadFailed', { name: attachment.fileName }));
    }
  };

  const cancelDelete = useCallback(() => setAttachmentToDelete(null), []);

  const handleDelete = async () => {
    if (!attachmentToDelete) {
      return;
    }
    try {
      await deleteTaskAttachment(taskId, attachmentToDelete.id);
      setAttachments(current => current.filter(item => item.id !== attachmentToDelete.id));
      toast.success(t('taskActions.attachmentDeleted'));
    } catch (error) {
      console.error('Error deleting attachment:', error);
      toast.error(t('notifications.errorOccurred', { message: error.message }));
    } finally {
      setAttachmentToDelete(null);
    }
  };

  return (
    <div
      className={`attachments-section${draggingFileOver ? ' drop-target' : ''}`}
      onDragOver={readOnly ? undefined : handleDragOver}
      onDragLeave={readOnly ? undefined : () => setDraggingFileOver(false)}
      onDrop={readOnly ? undefined : handleDrop}
    >
      <h4>{t('taskActions.attachments')}</h4>

      {!readOnly && (
        <div className="add-attachment-form">
          <input
            ref={inputRef}
            type="file"
            multiple
            className="attachment-file-input"
            onChange={handleInputChange}
            disabled={uploading}
            data-testid="attachment-input"
          />
          <button
            type="button"
            className="add-attachment-btn"
            onClick={() => inputRef.current?.click()}
            disabled={uploading}
          >
            {uploading ? t('taskActions.attachmentUploading') : t('taskActions.addAttachment')}
          </button>
          <span className="attachment-hint">{t('taskActions.attachmentHint')}</span>
        </div>
      )}

      {attachments.length > 0 ? (
        <div className="attachments-list">
          {attachments.map(attachment => (
            <div key={attachment.id} className="attachment-item">
              <button
                type="button"
                className="attachment-name"
                onClick={() => handleDownload(attachment)}
                title={t('taskActions.downloadAttachment')}
              >
                <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke="currentColor" width="16" height="16">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15.172 7l-6.586 6.586a2 2 0 102.828 2.828l6.414-6.586a4 4 0 00-5.656-5.656l-6.415 6.585a6 6 0 108.486 8.486L20.5 13" />
                </svg>
                <span className="attachment-file-name">{attachment.fileName}</span>
              </button>
              <span className="attachment-meta">
                {formatFileSize(attachment.sizeBytes)}
                {attachment.uploadedByName ? ` · ${attachment.uploadedByName}` : ''}
              </span>
              {!readOnly && (
                <button
                  type="button"
                  className="delete-attachment-btn"
                  onClick={() => setAttachmentToDelete(attachment)}
                  title={t('taskActions.deleteAttachment')}
                >
                  ×
                </button>
              )}
            </div>
          ))}
        </div>
      ) : loaded && (
        <p className="no-attachments">{t('taskActions.noAttachments')}</p>
      )}

      {attachmentToDelete && (
        <ConfirmDialog
          title={t('taskActions.confirmDeleteAttachment')}
          subject={attachmentToDelete.fileName}
          onConfirm={handleDelete}
          onCancel={cancelDelete}
        />
      )}
    </div>
  );
}

export default TaskAttachments;
