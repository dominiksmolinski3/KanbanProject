import { apiError } from './apiError';

const SUBTASKS = '/api/subtasks';

export const fetchSubTask = async (subTaskId) => {
  try {
    const response = await fetch(`${SUBTASKS}/${subTaskId}`);
    if (!response.ok) {
      throw await apiError(response);
    }
    return await response.json();
  } catch (error) {
    console.error(`Error fetching subtask ${subTaskId}:`, error);
    throw error;
  }
};

export const fetchSubTasksByTaskId = async (taskId) => {
  try {
    const response = await fetch(`${SUBTASKS}/task/${taskId}`);
    if (!response.ok) {
      throw await apiError(response);
    }
    return await response.json();
  } catch (error) {
    console.error(`Error fetching subtasks for task ${taskId}:`, error);
    throw error;
  }
};

export const addSubTask = async (taskId, title) => {
  try {
    const response = await fetch(SUBTASKS, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        title,
        completed: false,
        task: {
          id: taskId
        }
      })
    });
    
    if (!response.ok) {
      throw await apiError(response);
    }
    
    return await response.json();
  } catch (error) {
    console.error('Error adding subtask:', error);
    throw error;
  }
};

export const updateSubTask = async (subTaskId, subTaskData) => {
  try {
    const response = await fetch(`${SUBTASKS}/${subTaskId}`, {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(subTaskData)
    });
    
    if (!response.ok) {
      throw await apiError(response);
    }
    
    return await response.json();
  } catch (error) {
    console.error(`Error updating subtask ${subTaskId}:`, error);
    throw error;
  }
};

export const toggleSubTaskCompletion = async (subTaskId) => {
  try {
    const response = await fetch(`${SUBTASKS}/${subTaskId}/change`, {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json'
      }
    });
    
    if (!response.ok) {
      throw await apiError(response);
    }
    
    return await response.json();
  } catch (error) {
    console.error(`Error toggling completion for subtask ${subTaskId}:`, error);
    throw error;
  }
};

export const deleteSubTask = async (subTaskId) => {
  try {
    const response = await fetch(`${SUBTASKS}/${subTaskId}`, {
      method: 'DELETE'
    });
    
    if (!response.ok && response.status !== 404) {
      throw await apiError(response);
    }
    
    return true;
  } catch (error) {
    console.error(`Error deleting subtask ${subTaskId}:`, error);
    throw error;
  }
};
