import { useState, useRef } from 'react';
import { toast } from 'react-toastify';
import { useTranslation } from 'react-i18next';
import { updateTaskLabels } from '../services/api';
import { useKanban } from '../context/KanbanContext';
import type { Id, Task } from '../types/api';
import CustomLabelForm from './labels/CustomLabelForm';
import LabelChip from './labels/LabelChip';
import LabelPicker from './labels/LabelPicker';
import { DEFAULT_LABEL_COLOR } from './labels/labelPalette';
import { useLabelColors } from './labels/useLabelColors';
import { useLabelOverlays } from './labels/useLabelOverlays';
import '../styles/components/TaskLabels.css';

interface TaskLabelsProps {
  taskId: Id;
  initialLabels?: string[];
  onLabelsChange: (labels: string[], task?: Task) => void;
  readOnly?: boolean;
}

const TaskLabels = ({ taskId, initialLabels = [], onLabelsChange, readOnly = false }: TaskLabelsProps) => {
  const [labels, setLabels] = useState(initialLabels);
  const [customLabelName, setCustomLabelName] = useState('');
  const [customLabelColor, setCustomLabelColor] = useState(DEFAULT_LABEL_COLOR);
  const { refreshTasks } = useKanban();
  const buttonRef = useRef<HTMLButtonElement>(null);
  const { t } = useTranslation();
  const { existingLabels, colorOf, rememberColor, rememberLabel } = useLabelColors();
  const overlays = useLabelOverlays(buttonRef);

  const handleAddLabel = async (labelName: string) => {
    try {
      const labelExists = labels.some(existingLabel =>
        existingLabel.toLowerCase() === labelName.toLowerCase()
      );

      if (labelExists) {
        toast.warning(t('taskLabels.alreadyAddedWarning'));
        return;
      }

      const updatedLabels = [...labels, labelName];
      const updatedTask = await updateTaskLabels(taskId, updatedLabels);
      setLabels(updatedLabels);
      rememberLabel(labelName);

      onLabelsChange(updatedLabels, updatedTask);
      refreshTasks();
    } catch (error) {
      console.error('Error adding label:', error);
      toast.error(t('taskLabels.addErrorMessage'));
    }
  };

  const handleAddCustomLabel = async () => {
    if (!customLabelName.trim()) return;

    try {
      rememberColor(customLabelName, customLabelColor);

      await handleAddLabel(customLabelName);
      setCustomLabelName('');
      overlays.closeForm();
    } catch (error) {
      console.error('Error adding custom label:', error);
    }
  };

  const handleRemoveLabel = async (labelName: string) => {
    try {
      const updatedLabels = labels.filter(label => label !== labelName);
      const updatedTask = await updateTaskLabels(taskId, updatedLabels);
      setLabels(updatedLabels);
      onLabelsChange(updatedLabels, updatedTask);
    } catch (error) {
      console.error('Error removing label:', error);
      toast.error(t('taskLabels.removeErrorMessage'));
    }
  };

  const pick = (labelName: string) => {
    handleAddLabel(labelName);
    overlays.closePicker();
  };

  return (
    <div className="task-labels">
      <div className="labels-container">
        {labels.map((label) => (
          <LabelChip
            key={label}
            label={label}
            color={colorOf(label)}
            readOnly={readOnly}
            onRemove={handleRemoveLabel}
          />
        ))}
        {!readOnly && (
          <button
            ref={buttonRef}
            className="add-label-button"
            onClick={(e) => {
              e.stopPropagation();
              overlays.togglePicker();
            }}
          >
            {t('taskLabels.addLabel')}
          </button>
        )}
      </div>

      {overlays.picker.visible && (
        <LabelPicker
          position={overlays.picker.position}
          existingLabels={existingLabels}
          colorOf={colorOf}
          onPick={pick}
          onCustom={overlays.openForm}
        />
      )}

      {overlays.form.visible && (
        <CustomLabelForm
          position={overlays.form.position}
          name={customLabelName}
          color={customLabelColor}
          onNameChange={setCustomLabelName}
          onColorChange={setCustomLabelColor}
          onAdd={handleAddCustomLabel}
          onCancel={overlays.closeForm}
        />
      )}
    </div>
  );
};

export default TaskLabels;
