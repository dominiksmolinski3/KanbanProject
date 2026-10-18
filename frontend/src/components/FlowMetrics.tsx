import { useTranslation } from 'react-i18next';
import CumulativeFlow from './flow/CumulativeFlow';
import CycleTimes from './flow/CycleTimes';
import FlowFilters from './flow/FlowFilters';
import FlowTiles from './flow/FlowTiles';
import Throughput from './flow/Throughput';
import { useDuration } from './flow/useDuration';
import { useFlowMetrics } from './flow/useFlowMetrics';
import '../styles/components/FlowMetrics.css';

function FlowMetrics() {
  const { t } = useTranslation();
  const duration = useDuration();
  const flow = useFlowMetrics();
  const { data, loading, failed } = flow;

  return (
    <div className="flow-metrics viz-root page-shell">
      <div className="flow-header page-head">
        <h1 className="page-title">{t('flow.heading')}</h1>
        <p className="flow-explainer page-lede">{t('flow.explainer')}</p>
      </div>

      <FlowFilters flow={flow} />

      {loading && <p className="flow-empty">{t('flow.loading')}</p>}
      {!loading && failed && <p className="flow-empty">{t('flow.failed')}</p>}

      {!loading && data && (
        <>
          <FlowTiles data={data} duration={duration} />
          <CumulativeFlow data={data} />
          <CycleTimes data={data} duration={duration} />
          <Throughput data={data} />
        </>
      )}
    </div>
  );
}

export default FlowMetrics;
