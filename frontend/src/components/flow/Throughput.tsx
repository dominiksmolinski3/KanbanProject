import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { FlowMetrics } from '../../types/api';
import Axis from './Axis';
import { ChartTooltip, TooltipRow } from './ChartTooltip';
import { HEIGHT, MARGIN, PLOT_H, PLOT_W, WIDTH, niceMax, shortDate } from './chartGeometry';

export default function Throughput({ data }: { data: FlowMetrics }) {
  const { t } = useTranslation();
  const [hover, setHover] = useState<number | null>(null);
  const days = data.throughput;
  const max = niceMax(Math.max(1, ...days.map(d => d.count)));
  const slot = PLOT_W / days.length;
  const barW = Math.max(2, slot - 2);
  const x = (i: number) => MARGIN.left + i * slot + (slot - barW) / 2;
  const y = (v: number) => MARGIN.top + PLOT_H - (v / max) * PLOT_H;
  const center = (i: number) => MARGIN.left + i * slot + slot / 2;
  const radius = Math.min(4, barW / 2);

  return (
    <section className="flow-card">
      <h3>{t('flow.throughput')}</h3>
      <p className="flow-caption">{t('flow.throughputCaption')}</p>
      <div className="flow-chart">
        <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} role="img" aria-label={t('flow.throughput')}>
          <Axis max={max} y={y} days={days} x={center} counts />
          {days.map((day, i) => day.count > 0 && (
            <path key={day.date} className="flow-bar"
              d={`M${x(i)},${MARGIN.top + PLOT_H} V${y(day.count) + radius} a${radius},${radius} 0 0 1 ${radius},${-radius} H${x(i) + barW - radius} a${radius},${radius} 0 0 1 ${radius},${radius} V${MARGIN.top + PLOT_H} Z`} />
          ))}
          {days.map((day, i) => (
            <rect key={`hit-${day.date}`} className="flow-hit" x={MARGIN.left + i * slot} y={MARGIN.top} width={slot} height={PLOT_H}
              onMouseEnter={() => setHover(i)} onMouseLeave={() => setHover(null)} />
          ))}
        </svg>
        {hover !== null && (
          <ChartTooltip at={center(hover)} heading={shortDate(days[hover].date)}>
            <TooltipRow label={t('flow.finished')} value={days[hover].count} />
          </ChartTooltip>
        )}
      </div>
    </section>
  );
}
