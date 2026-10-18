import { useTranslation } from 'react-i18next';
import type { FlowMetrics } from '../../types/api';
import type { FormatDuration } from './useDuration';

function Tile({ label, value }: { label: string; value: string | number }) {
  return (
    <div className="flow-tile">
      <span className="flow-tile-label">{label}</span>
      <span className="flow-tile-value">{value}</span>
    </div>
  );
}

export default function FlowTiles({ data, duration }: { data: FlowMetrics; duration: FormatDuration }) {
  const { t } = useTranslation();

  return (
    <div className="flow-tiles">
      <Tile label={t('flow.finished')} value={data.cycleTime.count} />
      <Tile label={t('flow.median')} value={duration(data.cycleTime.medianHours)} />
      <Tile label={t('flow.p85')} value={duration(data.cycleTime.p85Hours)} />
      <Tile label={t('flow.average')} value={duration(data.cycleTime.averageHours)} />
    </div>
  );
}
