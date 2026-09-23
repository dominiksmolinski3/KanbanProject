import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import TaskDetails from '../../components/TaskDetails';
import KanbanContext from '../../context/KanbanContext';
import * as api from '../../services/api';

jest.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key) => key
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
 * FEAT-08 gated the board and never reached the task panel: a viewer who opened a card was offered
 * every edit in it - title, description, subtasks, attachments, labels, assignees - and each one
 * failed on the server. The panel now reads `readOnly` from the board context and draws what a
 * viewer can do, which is look. The same panel for a member keeps every control, which is the
 * control case: without it a panel that lost its buttons for everybody would pass as well.
 */
describe('TaskDetails for a viewer', () => {
  const task = { id: 1, title: 'Test Task', description: 'A task', labels: ['Bug'] };

  beforeEach(() => {
    jest.clearAllMocks();

    api.fetchTask.mockResolvedValue(task);
    api.fetchUsers.mockResolvedValue([]);
    api.fetchSubTasksByTaskId.mockResolvedValue([
      { id: 3, title: 'Subtask', completed: false }
    ]);
    api.getChildTasks.mockResolvedValue([]);
    api.getTaskColumnHistory.mockResolvedValue([]);
    api.fetchColumns.mockResolvedValue([]);
    api.getAllLabels.mockResolvedValue([]);
    api.fetchTaskAttachments.mockResolvedValue([{
      id: 5,
      taskId: 1,
      fileName: 'design.pdf',
      contentType: 'application/pdf',
      sizeBytes: 1024,
      uploadedById: 1,
      uploadedByName: 'John Doe',
      uploadedAt: '2026-04-01T10:15:30Z'
    }]);
    api.MAX_ATTACHMENT_SIZE = 10 * 1024 * 1024;

    console.error = jest.fn();
  });

  const renderPanel = (readOnly) =>
    render(
      <KanbanContext.Provider value={{ refreshTasks: jest.fn(), readOnly }}>
        <TaskDetails task={task} onClose={jest.fn()} onSubtaskUpdate={jest.fn()} />
      </KanbanContext.Provider>
    );

  const waitForPanel = async () => {
    await waitFor(() => expect(screen.getByText('design.pdf')).toBeInTheDocument());
    await waitFor(() => expect(screen.getByText('Subtask')).toBeInTheDocument());
  };

  test('offers no edit, add or delete control, and still shows the content', async () => {
    renderPanel(true);
    await waitForPanel();

    expect(screen.queryByTitle('taskActions.editTitle')).not.toBeInTheDocument();
    expect(screen.queryByTitle('taskActions.editTaskDescription')).not.toBeInTheDocument();
    expect(screen.queryByPlaceholderText('taskActions.shadowDescription')).not.toBeInTheDocument();
    expect(screen.queryByTitle('taskActions.deleteSubTask')).not.toBeInTheDocument();
    expect(screen.queryByText('taskActions.addAttachment')).not.toBeInTheDocument();
    expect(screen.queryByTitle('taskActions.deleteAttachment')).not.toBeInTheDocument();
    expect(screen.queryByText('taskLabels.addLabel')).not.toBeInTheDocument();

    expect(screen.getByRole('checkbox')).toBeDisabled();
    expect(screen.getByText('A task')).toBeInTheDocument();
    expect(screen.getByTitle('taskActions.downloadAttachment')).toBeInTheDocument();
    expect(screen.getByText('Bug')).toBeInTheDocument();
  });

  test('a member gets every one of those controls', async () => {
    renderPanel(false);
    await waitForPanel();

    expect(screen.getByTitle('taskActions.editTitle')).toBeInTheDocument();
    expect(screen.getByTitle('taskActions.editTaskDescription')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('taskActions.shadowDescription')).toBeInTheDocument();
    expect(screen.getByTitle('taskActions.deleteSubTask')).toBeInTheDocument();
    expect(screen.getByText('taskActions.addAttachment')).toBeInTheDocument();
    expect(screen.getByTitle('taskActions.deleteAttachment')).toBeInTheDocument();
    expect(screen.getByText('taskLabels.addLabel')).toBeInTheDocument();
    expect(screen.getByRole('checkbox')).not.toBeDisabled();
  });
});
