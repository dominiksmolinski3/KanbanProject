import { useTranslation } from 'react-i18next';
import FloatingLayer from './FloatingLayer';
import { PREDEFINED_LABELS } from './labelPalette';
import type { Position } from './labelLayout';

interface LabelPickerProps {
  position: Position;
  existingLabels: string[];
  colorOf: (label: string) => string;
  onPick: (label: string) => void;
  onCustom: () => void;
}

function Option({ label, color, onPick }: { label: string; color: string; onPick: (label: string) => void }) {
  return (
    <div className="label-option" onClick={() => onPick(label)}>
      <span className="color-dot" style={{ backgroundColor: color }}></span>
      {label}
    </div>
  );
}

export default function LabelPicker({ position, existingLabels, colorOf, onPick, onCustom }: LabelPickerProps) {
  const { t } = useTranslation();
  const others = existingLabels.filter(label => !PREDEFINED_LABELS.some(p => p.name === label));

  return (
    <FloatingLayer>
      <div
        className="label-picker animated fadeIn"
        style={{
          position: 'absolute',
          top: `${position.top}px`,
          left: `${position.left}px`,
          zIndex: 2000
        }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="label-picker-header">
          {t('taskLabels.chooseLabelHeader')}
        </div>

        <div className="label-section">
          <div className="section-title">{t('taskLabels.predefinedLabels')}</div>
          {PREDEFINED_LABELS.map((label) => (
            <Option key={label.name} label={label.name} color={label.color} onPick={onPick} />
          ))}
        </div>

        {existingLabels.length > 0 && (
          <div className="label-section">
            <div className="section-title">{t('taskLabels.existingLabels')}</div>
            {others.map((label) => (
              <Option key={label} label={label} color={colorOf(label)} onPick={onPick} />
            ))}
          </div>
        )}

        <div className="custom-label-option" onClick={onCustom}>
          <span className="color-dot custom"></span>
          {t('taskLabels.addCustomLabel')}
        </div>
      </div>
    </FloatingLayer>
  );
}
