import { useState, type FormEvent } from 'react';
import LengthHint from './LengthHint';
import { NAME_MAX_LENGTH } from '../services/textLimits';
import { useKanban } from '../context/KanbanContext';
import { useTranslation } from 'react-i18next';
import Icon from './Icon';
import FormModal from './FormModal';
import FormTabs from './ui/FormTabs';
import { useAsyncAction } from '../hooks/useAsyncAction';
import '../styles/components/Forms.css';

type Target = 'column' | 'row';

interface AddRowColumnFormProps {
  onClose?: () => void;
  defaultTab?: Target;
}

const noop = () => {};

function AddRowColumnForm({ onClose = noop, defaultTab = 'column' }: AddRowColumnFormProps) {
  const { addColumn, addRow } = useKanban();
  const [name, setName] = useState('');
  const [wipLimit, setWipLimit] = useState('0');
  const { pending: isSubmitting, error, setError, run } = useAsyncAction();
  const [activeTab, setActiveTab] = useState<Target>(defaultTab);
  const { t } = useTranslation();

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();

    if (!name.trim()) {
      setError(t(`forms.addRowColumn.error.nameRequired.${activeTab}`));
      return;
    }

    const added = await run(async () => {
      if (activeTab === 'column') {
        await addColumn(name, wipLimit);
      } else {
        await addRow(name, parseInt(wipLimit) || 0);
      }
    }, t(`forms.addRowColumn.error.addFailed.${activeTab}`));

    if (added) {
      setName('');
      setWipLimit('0');
      onClose();
    }
  };

  const switchTab = (tab: Target) => {
    setActiveTab(tab);
    setName('');
    setWipLimit('0');
    setError(null);
  };

  return (
    <FormModal onClose={onClose} ariaLabel={t('forms.addRowColumn.title')}>
      <div className="form-container">
        <form onSubmit={handleSubmit}>
          <div className="form-header">
            <h3>{t('forms.addRowColumn.title')}</h3>
            <button
              type="button"
              className="close-button"
              onClick={onClose}
              aria-label={t('forms.addRowColumn.close')}
            >
              <Icon name="close" />
            </button>
          </div>

          <FormTabs
            active={activeTab}
            onChange={switchTab}
            tabs={[
              { id: 'column', label: t('forms.addRowColumn.tabs.columns'), testId: 'add-row-column-tab-column' },
              { id: 'row', label: t('forms.addRowColumn.tabs.rows'), testId: 'add-row-column-tab-row' },
            ]}
          />

          {error && <div className="error-message">{error}</div>}

          <div className="form-group">
            <label htmlFor="item-name">
              {t(`forms.addRowColumn.nameLabel.${activeTab}`)}
            </label>
            <input
              id="item-name"
              type="text"
              value={name}
              maxLength={NAME_MAX_LENGTH}
              onChange={(e) => setName(e.target.value)}
              placeholder={t(`forms.addRowColumn.placeholder.${activeTab}`)}
              disabled={isSubmitting}
            />
            <LengthHint value={name} max={NAME_MAX_LENGTH} />
          </div>

          <div className="form-group">
            <label htmlFor="wip-limit">
              {t('forms.wipLimit.limitLabel')} <span className="help-text">({t('forms.wipLimit.noLimit')})</span>
            </label>
            <input
              id="wip-limit"
              type="number"
              min="0"
              value={wipLimit}
              onChange={(e) => setWipLimit(e.target.value)}
              placeholder="0"
              disabled={isSubmitting}
            />
          </div>

          <div className="form-actions">
            <button type="submit" disabled={isSubmitting}>
              {isSubmitting ? t('forms.addRowColumn.submitting') : t(`forms.addRowColumn.submit.${activeTab}`)}
            </button>
            <button type="button" className="cancel-button" onClick={onClose} disabled={isSubmitting}>
              {t('forms.wipLimit.cancel')}
            </button>
          </div>
        </form>
      </div>
    </FormModal>
  );
}

export default AddRowColumnForm;
