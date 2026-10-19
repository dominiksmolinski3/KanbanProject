import { useRef, type ChangeEvent } from 'react';
import { useTranslation } from 'react-i18next';
import Icon from '../../Icon';

interface AttachmentPickerProps {
  uploading: boolean;
  onFiles: (files: FileList | null) => void;
}

export default function AttachmentPicker({ uploading, onFiles }: AttachmentPickerProps) {
  const { t } = useTranslation();
  const input = useRef<HTMLInputElement>(null);

  const handleChange = (event: ChangeEvent<HTMLInputElement>) => {
    onFiles(event.target.files);
    // Reset so picking the same file again still fires a change event.
    event.target.value = '';
  };

  return (
    <div className="add-attachment-form">
      <input
        ref={input}
        type="file"
        multiple
        className="attachment-file-input"
        onChange={handleChange}
        disabled={uploading}
        data-testid="attachment-input"
      />
      <button
        type="button"
        className="add-attachment-btn"
        onClick={() => input.current?.click()}
        disabled={uploading}
      >
        <Icon name="paperclip" />
        <span className="attachment-add-label">
          {uploading ? t('taskActions.attachmentUploading') : t('taskActions.addAttachment')}
        </span>
        <span className="attachment-hint">{t('taskActions.attachmentHint')}</span>
      </button>
    </div>
  );
}
