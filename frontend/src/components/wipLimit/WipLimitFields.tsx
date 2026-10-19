import type { ChangeEvent } from 'react';
import { useTranslation } from 'react-i18next';
import type { ColumnSummary } from '../../types/api';
import type { WipTarget } from './useWipLimitForm';

interface WipLimitFieldsProps {
  target: WipTarget;
  items: ColumnSummary[];
  selectedItem: string;
  wipLimit: string;
  disabled: boolean;
  onSelect: (event: ChangeEvent<HTMLSelectElement>) => void;
  onLimit: (value: string) => void;
}

export default function WipLimitFields({ target, items, selectedItem, wipLimit, disabled, onSelect, onLimit }: WipLimitFieldsProps) {
  const { t } = useTranslation();
  const prompt = target === 'column' ? t('forms.wipLimit.selectColumn') : t('forms.wipLimit.selectRow');

  return (
    <>
      <div className="form-group">
        <label htmlFor="item-select">{prompt + ':'}</label>
        <select id="item-select" value={selectedItem} onChange={onSelect} disabled={disabled}>
          <option value="">{prompt}</option>
          {items.map(item => (
            <option key={item.id} value={item.id}>
              {item.name}
              {item.wipLimit > 0 ? ` (${t('forms.wipLimit.activeLimit')} ${item.wipLimit})` : ' (∞)'}
            </option>
          ))}
        </select>
      </div>

      <div className="form-group">
        <label htmlFor="wip-limit">
          {t('forms.wipLimit.limitLabel')} <span className="help-text">(0 = ∞)</span>
        </label>
        <input
          id="wip-limit"
          type="number"
          min="0"
          value={wipLimit}
          onChange={(e) => onLimit(e.target.value)}
          placeholder="0"
          disabled={disabled || !selectedItem}
          onInput={(e) => {
            if (Number(e.currentTarget.value) < 0) {
              e.currentTarget.value = '0';
              onLimit('0');
            }
          }}
        />
      </div>
    </>
  );
}
