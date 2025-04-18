import { useState } from 'react';
import { useKanban } from '../context/KanbanContext';
import '../styles/components/Forms.css'; 

function AddTaskForm({ onClose }) {
  const { addTask, refreshTasks } = useKanban();
  const [title, setTitle] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState(null);
  
  const handleSubmit = async (e) => {
    e.preventDefault();
    
    if (!title.trim()) {
      setError('Tytuł zadania jest wymagany!');
      return;
    }
    
    try {
      setIsSubmitting(true);
      setError(null);
      
      await addTask(title);
      await refreshTasks();
      
      // Reset form
      setTitle('');
      
      // Close form after successful submission
      if (onClose) onClose();
    } catch (err) {
      setError(err.message || 'Wystąpił błąd podczas dodawania zadania');
    } finally {
      setIsSubmitting(false);
    }
  };
  
  return (
    <div className="form-container">
      <form onSubmit={handleSubmit}>
        <div className="form-header">
          <h3>Dodaj nowe zadanie</h3>
          <button 
            type="button" 
            className="close-button" 
            onClick={onClose}
            aria-label="Zamknij formularz"
          >
            ×
          </button>
        </div>
        
        {error && <div className="error-message">{error}</div>}
        
        <div className="form-group">
          <label htmlFor="task-title">Tytuł zadania:</label>
          <input
            id="task-title"
            type="text"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="Wpisz tytuł zadania"
            disabled={isSubmitting}
          />
        </div>
        
        <div className="form-actions">
          <button 
            type="submit"
            disabled={isSubmitting}
          >
            {isSubmitting ? 'Dodawanie...' : 'Dodaj zadanie'}
          </button>
          <button 
            type="button" 
            className="cancel-button"
            onClick={onClose}
            disabled={isSubmitting}
          >
            Anuluj
          </button>
        </div>
      </form>
    </div>
  );
}

export default AddTaskForm;