import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { toast } from 'react-toastify';
import {
  AttachmentUploadError, MAX_ATTACHMENT_SIZE, deleteTaskAttachment, downloadTaskAttachment,
  fetchTaskAttachments, uploadTaskAttachment
} from '../../../services/api';
import { logged } from '../../../services/logged';
import { taskKeys } from '../../../services/taskQueries';
import type { Id, TaskAttachment } from '../../../types/api';
import { formatFileSize } from './fileSize';

const NONE: TaskAttachment[] = [];

export function useAttachments(taskId: Id) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const key = taskKeys.attachments(taskId);
  const [uploading, setUploading] = useState(false);

  const query = useQuery({
    queryKey: key,
    queryFn: async () => {
      const files = await logged('Error fetching task attachments:', () => fetchTaskAttachments(taskId));
      return Array.isArray(files) ? files : NONE;
    },
  });

  const reportUploadError = (error: unknown, fileName: string) => {
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

  const upload = async (files: FileList | File[] | null | undefined) => {
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
      await queryClient.invalidateQueries({ queryKey: key });
    } finally {
      setUploading(false);
    }
  };

  const download = async (attachment: TaskAttachment) => {
    try {
      await downloadTaskAttachment(taskId, attachment.id, attachment.fileName);
    } catch (error) {
      console.error('Error downloading attachment:', error);
      toast.error(t('taskActions.attachmentDownloadFailed', { name: attachment.fileName }));
    }
  };

  const remove = async (attachment: TaskAttachment) => {
    try {
      await deleteTaskAttachment(taskId, attachment.id);
      queryClient.setQueryData<TaskAttachment[]>(key, current =>
        (current ?? NONE).filter(item => item.id !== attachment.id));
      toast.success(t('taskActions.attachmentDeleted'));
    } catch (error) {
      console.error('Error deleting attachment:', error);
      toast.error(t('notifications.errorOccurred', { message: (error as Error).message }));
    }
  };

  return { attachments: query.data ?? NONE, loaded: !query.isPending, uploading, upload, download, remove };
}
