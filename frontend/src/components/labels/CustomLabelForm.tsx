import { useTranslation } from 'react-i18next';
import { LABEL_MAX_LENGTH } from '../../services/textLimits';
import LengthHint from '../LengthHint';
import FloatingLayer from './FloatingLayer';
import type { Position } from './labelLayout';

interface CustomLabelFormProps {
  position: Position;
  name: string;
  color: string;
  onNameChange: (name: string) => void;
  onColorChange: (color: string) => void;
  onAdd: () => void;
  onCancel: () => void;
}

export default function CustomLabelForm({ position, name, color, onNameChange, onColorChange, onAdd, onCancel }: CustomLabelFormProps) {
  const { t } = useTranslation();
  const blank = !name.trim();

  return (
    <FloatingLayer>
      <div
        className="custom-label-form-portal animated fadeIn"
        style={{
          position: 'fixed',
          top: `${position.top}px`,
          left: `${position.left}px`,
          zIndex: 3000
        }}
        onClick={(e) => e.stopPropagation()}
      >
        <h3 className="form-title">{t('taskLabels.addCustomLabelTitle')}</h3>
        <div className="form-group">
          <label>{t('taskLabels.labelName')}</label>
          <input
            type="text"
            value={name}
            maxLength={LABEL_MAX_LENGTH}
            onChange={(e) => onNameChange(e.target.value)}
            placeholder={t('taskLabels.labelNamePlaceholder')}
            autoFocus
          />
          <LengthHint value={name} max={LABEL_MAX_LENGTH} />
        </div>
        <div className="form-group">
          <label>{t('taskLabels.labelColor')}</label>
          <input type="color" value={color} onChange={(e) => onColorChange(e.target.value)} />
        </div>
        <div className="form-actions">
          <button onClick={onAdd} disabled={blank} className={`add-button ${blank ? 'disabled' : ''}`}>
            {t('taskLabels.add')}
          </button>
          <button onClick={onCancel} className="cancel-button">
            {t('taskLabels.cancel')}
          </button>
        </div>
      </div>
    </FloatingLayer>
  );
}
