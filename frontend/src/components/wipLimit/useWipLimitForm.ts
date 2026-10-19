import { useState, type ChangeEvent, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useKanban } from '../../context/KanbanContext';
import { useAsyncAction } from '../../hooks/useAsyncAction';

export type WipTarget = 'column' | 'row';

const noop = () => {};

export function useWipLimitForm(onClose: () => void = noop) {
  const { columns, rows, updateWipLimit, updateRowWipLimit } = useKanban();
  const { t } = useTranslation();
  const { pending, error, setError, run } = useAsyncAction();
  const [target, setTarget] = useState<WipTarget>('column');
  const [selectedItem, setSelectedItem] = useState('');
  const [wipLimit, setWipLimit] = useState('');

  const items = target === 'column' ? columns : rows;

  const selectItem = (event: ChangeEvent<HTMLSelectElement>) => {
    const itemId = event.target.value;
    setSelectedItem(itemId);
    const item = items.find(candidate => String(candidate.id) === itemId);
    if (!itemId) {
      setWipLimit('');
    } else if (item) {
      setWipLimit(String(item.wipLimit));
    }
  };

  const switchTarget = (next: WipTarget) => {
    setTarget(next);
    setSelectedItem('');
    setWipLimit('');
    setError(null);
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();

    const item = items.find(candidate => String(candidate.id) === selectedItem);
    if (!item) {
      const type = target === 'column' ? t('forms.wipLimit.columnTab') : t('forms.wipLimit.rowTab');
      setError(t('forms.wipLimit.error.selectRequired', { type: type.toLowerCase() }));
      return;
    }

    const parsedLimit = Number(wipLimit);
    if (wipLimit.trim() === '' || isNaN(parsedLimit) || parsedLimit < 0) {
      setError(t('forms.wipLimit.error.invalidLimit'));
      return;
    }

    const updated = await run(
      () => (target === 'column' ? updateWipLimit(item.id, parsedLimit) : updateRowWipLimit(item.id, parsedLimit)),
      t('notifications.errorOccurred', { message: '' })
    );

    if (updated) {
      setSelectedItem('');
      setWipLimit('');
      onClose();
    }
  };

  return { columns, rows, items, target, selectedItem, wipLimit, setWipLimit, pending, error, selectItem, switchTarget, submit };
}
