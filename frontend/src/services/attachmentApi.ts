import { apiError, type ErrorBody } from './apiError';
import { deleteQuietly, getJson } from './http';
import type { Id, TaskAttachment } from '../types/api';

const TASKS = '/api/tasks';

const attachmentsOf = (taskId: Id) => `${TASKS}/${taskId}/attachments`;

export const MAX_ATTACHMENT_SIZE = 10 * 1024 * 1024;

type UploadFailure = 'tooLarge' | 'storageUnavailable' | 'failed';

export class AttachmentUploadError extends Error {
  reason: UploadFailure;
  status: number;

  constructor(reason: UploadFailure, status: number) {
    super(`Attachment upload failed: ${reason}`);
    this.name = 'AttachmentUploadError';
    this.reason = reason;
    this.status = status;
  }
}

export const fetchTaskAttachments = (taskId: Id) => getJson<TaskAttachment[]>(attachmentsOf(taskId));

export const uploadTaskAttachment = async (taskId: Id, file: File): Promise<TaskAttachment> => {
  if (file.size > MAX_ATTACHMENT_SIZE) {
    throw new AttachmentUploadError('tooLarge', 413);
  }

  const formData = new FormData();
  formData.append('file', file);

  const response = await fetch(attachmentsOf(taskId), {
    method: 'POST',
    body: formData
  });

  if (!response.ok) {
    const body: ErrorBody = await response.json().catch(() => ({}));

    if (response.status === 413 || body.code === 'ATTACHMENT_TOO_LARGE') {
      throw new AttachmentUploadError('tooLarge', response.status);
    }
    if (response.status === 503 || body.code === 'ATTACHMENT_STORAGE_UNAVAILABLE') {
      throw new AttachmentUploadError('storageUnavailable', response.status);
    }
    throw new AttachmentUploadError('failed', response.status);
  }

  return await response.json();
};

const DOWNLOAD_RESUME_ATTEMPTS = 3;

interface DownloadState {
  chunks: BlobPart[];
  received: number;
}

const collectInto = async (url: string, state: DownloadState) => {
  const response = state.received > 0
    ? await fetch(url, { headers: { Range: `bytes=${state.received}-` } })
    : await fetch(url);

  if (!response.ok) {
    throw Object.assign(await apiError(response), { fatal: true });
  }

  if (state.received > 0 && response.status !== 206) {
    state.chunks = [];
    state.received = 0;
  }

  if (!response.body || typeof response.body.getReader !== 'function') {
    const whole = await response.blob();
    state.chunks.push(whole);
    state.received += whole.size;
    return;
  }

  const reader = response.body.getReader();
  for (;;) {
    const { done, value } = await reader.read();
    if (done) {
      return;
    }
    state.chunks.push(value);
    state.received += value.byteLength;
  }
};

export const downloadTaskAttachment = async (taskId: Id, attachmentId: number, fileName?: string) => {
  const url = `${attachmentsOf(taskId)}/${attachmentId}/content`;
  const state: DownloadState = { chunks: [], received: 0 };

  for (let attempt = 0; ; attempt++) {
    try {
      await collectInto(url, state);
      break;
    } catch (error) {
      if ((error as { fatal?: boolean }).fatal || state.received === 0 || attempt >= DOWNLOAD_RESUME_ATTEMPTS) {
        throw error;
      }
    }
  }

  const [first] = state.chunks;
  const blob = state.chunks.length === 1 && first instanceof Blob ? first : new Blob(state.chunks);
  const objectUrl = URL.createObjectURL(blob);

  const link = document.createElement('a');
  link.href = objectUrl;
  link.download = fileName || 'attachment';
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(objectUrl);

  return true;
};

export const deleteTaskAttachment = (taskId: Id, attachmentId: number) =>
  deleteQuietly(`${attachmentsOf(taskId)}/${attachmentId}`);
