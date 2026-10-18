import { useTranslation } from 'react-i18next';
import { useKanban } from '../../context/KanbanContext';
import type { FlowState } from './useFlowMetrics';

const WINDOWS = [14, 30, 90, 180];

export default function FlowFilters({ flow }: { flow: FlowState }) {
  const { t } = useTranslation();
  const { activeBoard } = useKanban();
  const { data, columns } = flow;

  return (
    <div className="flow-filters">
      <label className="field">
        <span className="field-label">{t('flow.window')}</span>
        <select className="field-input" value={flow.days} onChange={(event) => flow.setDays(Number(event.target.value))}>
          {WINDOWS.map(n => (
            <option key={n} value={n}>{t('flow.lastDays', { count: n })}</option>
          ))}
        </select>
      </label>
      <label className="field">
        <span className="field-label">{t('flow.startsAt')}</span>
        <select className="field-input" value={flow.start} onChange={(event) => flow.setStart(event.target.value)}>
          <option value="">
            {flow.definedStart ? t('flow.asTheBoardDefines', { name: flow.definedStart }) : t('flow.arrivalOnBoard')}
          </option>
          {columns.map(column => (
            <option key={column.id} value={column.id}>{column.name}</option>
          ))}
        </select>
      </label>
      <label className="field">
        <span className="field-label">{t('flow.doneAt')}</span>
        <select className="field-input" value={flow.done} onChange={(event) => flow.setDone(event.target.value)}>
          <option value="">
            {flow.definedDone ? t('flow.asTheBoardDefines', { name: flow.definedDone }) : t('flow.lastColumn')}
          </option>
          {columns.map(column => (
            <option key={column.id} value={column.id}>{column.name}</option>
          ))}
        </select>
      </label>
      {activeBoard?.owned && data && (
        <div className="flow-definition">
          {flow.differsFromBoard && (
            <button
              type="button"
              className="flow-definition-save btn btn-primary btn-sm"
              disabled={flow.saving}
              onClick={() => flow.saveDefinition({ start: data.startColumnId, done: data.doneColumnId })}
            >
              {t('flow.saveDefinition')}
            </button>
          )}
          {flow.hasDefinition && (
            <button
              type="button"
              className="flow-definition-reset btn btn-ghost btn-sm"
              disabled={flow.saving}
              onClick={() => flow.saveDefinition({ start: null, done: null })}
            >
              {t('flow.resetDefinition')}
            </button>
          )}
        </div>
      )}
    </div>
  );
}
