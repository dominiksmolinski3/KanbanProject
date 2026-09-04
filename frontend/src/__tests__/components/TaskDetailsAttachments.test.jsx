import React from 'react';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import '@testing-library/jest-dom';
import TaskDetails from '../../components/TaskDetails';
import { KanbanProvider } from '../../context/KanbanContext';
import * as api from '../../services/api';
import { toast } from 'react-toastify';

jest.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key, values) => (values ? `${key}:${JSON.stringify(values)}` : key)
  })
}));

jest.mock('react-toastify', () => ({
  toast: {
    error: jest.fn(),
    success: jest.fn(),
    info: jest.fn(),
    warning: jest.fn()
  }
}));

jest.mock('../../services/api');

/**
 * The attachment section of the task panel.
 *
 * What is worth testing here is the part a person actually does: pick a file, see it listed, click
 * it to download, and be asked before one is deleted. The confirmation is not a nicety - deleting
 * an attachment takes the blob as well as the row, and nothing in this panel brings it back.
 *
 * The refusals are here too, because they are the reason `AttachmentUploadError` carries a reason
 * at all: "too large" is something the person can act on and "storage is not configured" is not,
 * and a single message would send them to retry the one that can never work.
 */
describe('TaskDetails attachments', () => {
  const task = { id: 1, title: 'Test Task', description: 'A task', labels: [] };

  const attachment = {
    id: 5,
    taskId: 1,
    fileName: 'design.pdf',
    contentType: 'application/pdf',
    sizeBytes: 2 * 1024 * 1024,
    uploadedById: 1,
    uploadedByName: 'John Doe',
    uploadedAt: '2026-04-01T10:15:30Z'
  };

  const fileNamed = (name, size = 1024) => {
    const file = new File(['x'], name, { type: 'application/pdf' });
    Object.defineProperty(file, 'size', { value: size });
    return file;
  };

  beforeEach(() => {
    jest.clearAllMocks();

    api.fetchTask.mockResolvedValue(task);
    api.fetchUsers.mockResolvedValue([]);
    api.fetchSubTasksByTaskId.mockResolvedValue([]);
    api.getChildTasks.mockResolvedValue([]);
    api.getTaskColumnHistory.mockResolvedValue([]);
    api.fetchColumns.mockResolvedValue([]);
    api.fetchTaskAttachments.mockResolvedValue([attachment]);
    api.MAX_ATTACHMENT_SIZE = 10 * 1024 * 1024;
    api.AttachmentUploadError = class AttachmentUploadError extends Error {
      constructor(reason, status) {
        super(reason);
        this.name = 'AttachmentUploadError';
        this.reason = reason;
        this.status = status;
      }
    };

    console.error = jest.fn();
  });

  const renderPanel = () =>
    render(
      <KanbanProvider value={{ refreshTasks: jest.fn() }}>
        <TaskDetails task={task} onClose={jest.fn()} onSubtaskUpdate={jest.fn()} />
      </KanbanProvider>
    );

  const waitForPanel = async () => {
    await waitFor(() => expect(api.fetchTaskAttachments).toHaveBeenCalledWith(task.id));
    await waitFor(() => expect(screen.queryByText('board.loading')).not.toBeInTheDocument());
  };

  test('lists the files on the task, with their size and who added them', async () => {
    renderPanel();
    await waitForPanel();

    expect(screen.getByText('design.pdf')).toBeInTheDocument();
    expect(screen.getByText(/2\.0 MB/)).toBeInTheDocument();
    expect(screen.getByText(/John Doe/)).toBeInTheDocument();
  });

  test('says so when there are none rather than showing an empty box', async () => {
    api.fetchTaskAttachments.mockResolvedValue([]);

    renderPanel();
    await waitForPanel();

    expect(screen.getByText('taskActions.noAttachments')).toBeInTheDocument();
  });

  test('a chosen file is uploaded and the list is read back', async () => {
    api.uploadTaskAttachment.mockResolvedValue({ ...attachment, id: 6, fileName: 'notes.pdf' });

    renderPanel();
    await waitForPanel();

    fireEvent.change(screen.getByTestId('attachment-input'), {
      target: { files: [fileNamed('notes.pdf')] }
    });

    await waitFor(() => expect(api.uploadTaskAttachment).toHaveBeenCalledWith(task.id, expect.any(File)));
    // Twice: once on load, once after the upload - the row the server wrote is the one shown.
    await waitFor(() => expect(api.fetchTaskAttachments).toHaveBeenCalledTimes(2));
  });

  test('a file dropped on the section is uploaded too', async () => {
    api.uploadTaskAttachment.mockResolvedValue(attachment);

    renderPanel();
    await waitForPanel();

    // The panel renders through a portal, so it is in the document rather than in the container.
    fireEvent.drop(document.querySelector('.attachments-section'), {
      dataTransfer: { files: [fileNamed('dropped.pdf')] }
    });

    await waitFor(() => expect(api.uploadTaskAttachment).toHaveBeenCalled());
  });

  test('a file the server says is too large names the limit rather than just failing', async () => {
    api.uploadTaskAttachment.mockRejectedValue(new api.AttachmentUploadError('tooLarge', 413));

    renderPanel();
    await waitForPanel();

    fireEvent.change(screen.getByTestId('attachment-input'), {
      target: { files: [fileNamed('huge.pdf')] }
    });

    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith(expect.stringContaining('taskActions.attachmentTooLarge')));
  });

  test('storage not being configured is reported as its own thing', async () => {
    api.uploadTaskAttachment.mockRejectedValue(new api.AttachmentUploadError('storageUnavailable', 503));

    renderPanel();
    await waitForPanel();

    fireEvent.change(screen.getByTestId('attachment-input'), {
      target: { files: [fileNamed('notes.pdf')] }
    });

    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith('taskActions.attachmentStorageUnavailable'));
  });

  test('clicking a file downloads it under the name it was uploaded with', async () => {
    api.downloadTaskAttachment.mockResolvedValue(true);

    renderPanel();
    await waitForPanel();

    fireEvent.click(screen.getByText('design.pdf'));

    await waitFor(() => expect(api.downloadTaskAttachment)
      .toHaveBeenCalledWith(task.id, attachment.id, attachment.fileName));
  });

  test('deleting asks first, and only then removes it', async () => {
    api.deleteTaskAttachment.mockResolvedValue(true);

    renderPanel();
    await waitForPanel();

    fireEvent.click(document.querySelector('.delete-attachment-btn'));

    const dialog = await screen.findByText('taskActions.confirmDeleteAttachment');
    expect(api.deleteTaskAttachment).not.toHaveBeenCalled();

    const confirmation = dialog.closest('.delete-confirmation-dialog');
    fireEvent.click(within(confirmation).getByText('taskActions.yes'));

    await waitFor(() => expect(api.deleteTaskAttachment).toHaveBeenCalledWith(task.id, attachment.id));
    await waitFor(() => expect(screen.queryByText('design.pdf')).not.toBeInTheDocument());
  });

  test('backing out of the confirmation deletes nothing', async () => {
    renderPanel();
    await waitForPanel();

    fireEvent.click(document.querySelector('.delete-attachment-btn'));

    const dialog = await screen.findByText('taskActions.confirmDeleteAttachment');
    fireEvent.click(within(dialog.closest('.delete-confirmation-dialog')).getByText('taskActions.no'));

    await waitFor(() =>
      expect(screen.queryByText('taskActions.confirmDeleteAttachment')).not.toBeInTheDocument());
    expect(api.deleteTaskAttachment).not.toHaveBeenCalled();
    expect(screen.getByText('design.pdf')).toBeInTheDocument();
  });

  test('a listing that fails costs the panel its attachments and nothing else', async () => {
    api.fetchTaskAttachments.mockRejectedValue(new Error('storage is off'));

    renderPanel();
    await waitForPanel();

    expect(screen.getByText('Test Task')).toBeInTheDocument();
    expect(screen.getByText('taskActions.noAttachments')).toBeInTheDocument();
  });
});
