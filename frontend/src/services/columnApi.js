import { apiError } from './apiError';
import { onActiveBoard } from './activeBoard';
import { reorder } from './reorder';

const COLUMNS = '/api/columns';

export const fetchColumns = async (retries = 3) => {
  while (retries > 0) {
    try {
      const response = await fetch(onActiveBoard(COLUMNS));
      if (!response.ok) {
        throw await apiError(response);
      }
      return await response.json();
    } catch (error) {
      console.error('Error fetching columns:', error);
      if (retries === 1) throw error;
      retries--;
      await new Promise(resolve => setTimeout(resolve, 1000));
    }
  }
};

export const addColumn = async (name, wipLimit) => {
  try {
    const response = await fetch(onActiveBoard(COLUMNS), {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        name,
        wipLimit: parseInt(wipLimit) || 0
      })
    });
    
    if (!response.ok) {
      throw await apiError(response);
    }
    
    return await response.json();
  } catch (error) {
    console.error('Error adding column:', error);
    throw error;
  }
};

export const updateColumnWipLimit = async (columnId, wipLimit) => {
  try {
    const response = await fetch(`${COLUMNS}/${columnId}`, {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        wipLimit: parseInt(wipLimit)
      })
    });

    if (response.status >= 200 && response.status < 300) {
      try {
        return await response.json();
      } catch (parseError) {
        console.warn('JSON parse error but update likely succeeded:', parseError);
        return {
          id: columnId,
          wipLimit: parseInt(wipLimit)
        };
      }
    }

    throw await apiError(response);
  } catch (error) {
    console.error(`Error updating WIP limit for column ${columnId}:`, error);
    throw error;
  }
};

export const updateColumnPosition = async (columnId, position) => {
  try {
    const response = await fetch(`${COLUMNS}/${columnId}/position/${position}`, {
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
    console.error(`Error updating position for column ${columnId}:`, error);
    throw error;
  }
};

export const deleteColumn = async (columnId) => {
  try {
    const response = await fetch(`${COLUMNS}/${columnId}`, {
      method: 'DELETE'
    });
    
    if (!response.ok && response.status !== 404) {
      throw await apiError(response);
    }
    
    return true;
  } catch (error) {
    console.error(`Error deleting column ${columnId}:`, error);
    throw error;
  }
};

export const updateColumnName = async (id, name) => {
  try {
    const response = await fetch(`${COLUMNS}/${id}`, {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ name }),
    });
    
    if (!response.ok) {
      throw await apiError(response);
    }
    
    return await response.json();
  } catch (error) {
    console.error('Error updating column name:', error);
    throw error;
  }
};

export const reorderColumns = (orderedIds) => reorder(COLUMNS, orderedIds, 'column');
