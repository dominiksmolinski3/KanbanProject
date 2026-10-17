import { read, readDeleted } from './http';

const SUBTASKS = '/api/subtasks';

export const fetchSubTask = async (subTaskId) => {
  const response = await fetch(`${SUBTASKS}/${subTaskId}`);
  return read(response);
};

export const fetchSubTasksByTaskId = async (taskId) => {
  const response = await fetch(`${SUBTASKS}/task/${taskId}`);
  return read(response);
};

export const addSubTask = async (taskId, title) => {
  const response = await fetch(SUBTASKS, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title, completed: false, task: { id: taskId } })
  });
  return read(response);
};

export const updateSubTask = async (subTaskId, subTaskData) => {
  const response = await fetch(`${SUBTASKS}/${subTaskId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(subTaskData)
  });
  return read(response);
};

export const toggleSubTaskCompletion = async (subTaskId) => {
  const response = await fetch(`${SUBTASKS}/${subTaskId}/change`, { method: 'PATCH' });
  return read(response);
};

export const deleteSubTask = async (subTaskId) => {
  const response = await fetch(`${SUBTASKS}/${subTaskId}`, { method: 'DELETE' });
  return readDeleted(response);
};
