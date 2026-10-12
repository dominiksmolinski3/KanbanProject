import { apiError } from './apiError';
import { onActiveBoard } from './activeBoard';
import { reorder } from './reorder';

const ROWS = '/api/rows';

export const fetchRows = async (retries = 3) => {
  while (retries > 0) {
    try {
      const response = await fetch(onActiveBoard(ROWS));
      if (!response.ok) {
        throw await apiError(response);
      }
      return await response.json();
    } catch (error) {
      console.error('Error fetching rows:', error);
      if (retries === 1) throw error;
      retries--;
      await new Promise(resolve => setTimeout(resolve, 1000)); 
    }
  }
};

export const addRow = async (name, wipLimit) => {
  try {
    const response = await fetch(onActiveBoard(ROWS), {
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
    console.error('Error adding row:', error);
    throw error;
  }
};

export const updateRowWipLimit = async (rowId, wipLimit) => {
  try {
    const response = await fetch(`${ROWS}/${rowId}`, {
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
          id: rowId,
          wipLimit: parseInt(wipLimit)
        };
      }
    }

    throw await apiError(response);
  } catch (error) {
    console.error(`Error updating WIP limit for row ${rowId}:`, error);
    throw error;
  }
};

export const updateRowPosition = async (rowId, position) => {
  try {
    const response = await fetch(`${ROWS}/${rowId}/position/${position}`, {
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
    console.error(`Error updating position for row ${rowId}:`, error);
    throw error;
  }
};

export const deleteRow = async (rowId) => {
  try {
    const response = await fetch(`${ROWS}/${rowId}`, {
      method: 'DELETE'
    });
    
    if (!response.ok && response.status !== 404) {
      throw await apiError(response);
    }
    
    return true;
  } catch (error) {
    console.error(`Error deleting row ${rowId}:`, error);
    throw error;
  }
};

export const updateRowName = async (id, name) => {
  try {
    const response = await fetch(`${ROWS}/${id}`, {
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
    console.error('Error updating row name:', error);
    throw error;
  }
};

export const reorderRows = (orderedIds) => reorder(ROWS, orderedIds, 'swimlane');
