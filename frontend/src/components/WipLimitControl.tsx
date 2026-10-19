import { useTranslation } from 'react-i18next';
import Icon from './Icon';
import FormModal from './FormModal';
import WipLimitFields from './wipLimit/WipLimitFields';
import { useWipLimitForm, type WipTarget } from './wipLimit/useWipLimitForm';
import '../styles/components/Forms.css';

const noop = () => {};

function WipLimitControl({ onClose = noop }: { onClose?: () => void }) {
  const { t } = useTranslation();
  const form = useWipLimitForm(onClose);
  const { columns, rows, target } = form;

  if (columns.length === 0 && rows.length === 0) {
    return null;
  }

  const tabs: { id: WipTarget; label: string; empty: boolean }[] = [
    { id: 'column', label: t('forms.wipLimit.columnTab'), empty: columns.length === 0 },
    { id: 'row', label: t('forms.wipLimit.rowTab'), empty: rows.length === 0 },
  ];

  return (
    <FormModal onClose={onClose} ariaLabel={t('forms.wipLimit.title')}>
      <div className="form-container">
        <form onSubmit={form.submit} role="form">
          <div className="form-header">
            <h3>{t('forms.wipLimit.title')}</h3>
            <button type="button" className="close-button" onClick={onClose} aria-label={t('forms.wipLimit.close')}>
              <Icon name="close" />
            </button>
          </div>

          <div className="tab-container">
            {tabs.map(tab => (
              <button
                key={tab.id}
                type="button"
                className={`tab-btn ${target === tab.id ? 'active' : ''}`}
                onClick={() => form.switchTarget(tab.id)}
                disabled={tab.empty}
              >
                {tab.label}
              </button>
            ))}
          </div>

          {form.error && <div className="error-message">{form.error}</div>}

          <WipLimitFields
            target={target}
            items={form.items}
            selectedItem={form.selectedItem}
            wipLimit={form.wipLimit}
            disabled={form.pending}
            onSelect={form.selectItem}
            onLimit={form.setWipLimit}
          />

          <div className="form-actions">
            <button type="submit" disabled={form.pending || !form.selectedItem}>
              {form.pending ? t('forms.wipLimit.submitting') : t('forms.wipLimit.submit')}
            </button>
            <button type="button" className="cancel-button" onClick={onClose} disabled={form.pending}>
              {t('forms.wipLimit.cancel')}
            </button>
          </div>
        </form>
      </div>
    </FormModal>
  );
}

export default WipLimitControl;
