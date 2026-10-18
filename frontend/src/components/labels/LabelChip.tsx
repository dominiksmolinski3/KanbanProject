import Icon from '../Icon';

interface LabelChipProps {
  label: string;
  color: string;
  readOnly: boolean;
  onRemove: (label: string) => void;
}

export default function LabelChip({ label, color, readOnly, onRemove }: LabelChipProps) {
  return (
    <span className="label" title={label} data-tooltip={label}>
      <span className="label-color-dot" style={{ backgroundColor: color }}></span>
      <span className="label-text">{label}</span>
      {!readOnly && (
        <button
          onClick={(e) => {
            e.stopPropagation();
            onRemove(label);
          }}
          className="remove-label-button"
          aria-label={label}
        >
          <Icon name="close" size="sm" />
        </button>
      )}
    </span>
  );
}
