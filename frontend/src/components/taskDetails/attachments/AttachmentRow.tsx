import { useTranslation } from 'react-i18next';
import type { TaskAttachment } from '../../../types/api';
import Icon from '../../Icon';
import { formatFileSize } from './fileSize';

interface AttachmentRowProps {
  attachment: TaskAttachment;
  readOnly: boolean;
  onDownload: () => void;
  onAskDelete: () => void;
}

export default function AttachmentRow({ attachment, readOnly, onDownload, onAskDelete }: AttachmentRowProps) {
  const { t } = useTranslation();

  return (
    <div className="attachment-item">
      <button type="button" className="attachment-name" onClick={onDownload} title={t('taskActions.downloadAttachment')}>
        <Icon name="file" size="sm" />
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
          onClick={onAskDelete}
          title={t('taskActions.deleteAttachment')}
          aria-label={t('taskActions.deleteAttachment')}
        >
          <Icon name="trash" size="sm" />
        </button>
      )}
    </div>
  );
}
