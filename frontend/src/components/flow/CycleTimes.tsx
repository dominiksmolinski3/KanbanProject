import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { FlowMetrics } from '../../types/api';
import Axis from './Axis';
import { ChartTooltip, TooltipRow } from './ChartTooltip';
import { HEIGHT, MARGIN, PLOT_H, PLOT_W, WIDTH, niceMax } from './chartGeometry';
import type { FormatDuration } from './useDuration';

const DAY_MS = 86400000;

export default function CycleTimes({ data, duration }: { data: FlowMetrics; duration: FormatDuration }) {
  const { t } = useTranslation();
  const [hover, setHover] = useState<number | null>(null);
  const samples = data.samples;
  const days = data.cumulativeFlow;
  const first = new Date(`${data.from}T00:00:00`).getTime();
  const span = new Date(`${data.to}T00:00:00`).getTime() + DAY_MS - first;
  const max = niceMax(Math.max(1, ...samples.map(s => s.hours)));
  const x = (when: string) => MARGIN.left + ((new Date(when).getTime() - first) / span) * PLOT_W;
  const y = (h: number) => MARGIN.top + PLOT_H - (h / max) * PLOT_H;
  const dayX = (i: number) => MARGIN.left + (days.length === 1 ? PLOT_W / 2 : (i / (days.length - 1)) * PLOT_W);
  const { medianHours, p85Hours } = data.cycleTime;
  const references = [['median', medianHours], ['p85', p85Hours]] as const;

  return (
    <section className="flow-card">
      <h3>{t('flow.cycleTime')}</h3>
      <p className="flow-caption">{t('flow.cycleTimeCaption')}</p>
      {samples.length === 0 ? (
        <p className="flow-empty">{t('flow.nothingFinished')}</p>
      ) : (
        <div className="flow-chart">
          <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} role="img" aria-label={t('flow.cycleTime')}>
            <Axis max={max} y={y} days={days} x={dayX} format={duration} />
            {references.map(([key, value]) => value !== null && (
              <g key={key} className="flow-reference">
                <line x1={MARGIN.left} x2={WIDTH - MARGIN.right} y1={y(value)} y2={y(value)} />
                <text x={WIDTH - MARGIN.right} y={y(value) - 4} textAnchor="end">
                  {t(`flow.${key}Line`, { value: duration(value) })}
                </text>
              </g>
            ))}
            {samples.map((sample, i) => (
              <circle key={`${sample.taskId}-${i}`} cx={x(sample.doneAt)} cy={y(sample.hours)} r={hover === i ? 6 : 4.5}
                className="flow-dot" />
            ))}
            {samples.map((sample, i) => (
              <circle key={`hit-${sample.taskId}-${i}`} cx={x(sample.doneAt)} cy={y(sample.hours)} r={12}
                className="flow-hit" onMouseEnter={() => setHover(i)} onMouseLeave={() => setHover(null)} />
            ))}
          </svg>
          {hover !== null && (
            <ChartTooltip at={x(samples[hover].doneAt)} heading={samples[hover].title}>
              <TooltipRow label={t('flow.took')} value={duration(samples[hover].hours)} />
              <TooltipRow label={t('flow.finishedOn')} value={new Date(samples[hover].doneAt).toLocaleString()} />
            </ChartTooltip>
          )}
        </div>
      )}
      {samples.length > 0 && (
        <details className="flow-table">
          <summary>{t('flow.showTable')}</summary>
          <div className="flow-table-scroll">
            <table>
              <thead>
                <tr>
                  <th scope="col">{t('flow.card')}</th>
                  <th scope="col">{t('flow.finishedOn')}</th>
                  <th scope="col">{t('flow.took')}</th>
                </tr>
              </thead>
              <tbody>
                {samples.map((sample, i) => (
                  <tr key={`${sample.taskId}-${i}`}>
                    <td>{sample.title}</td>
                    <td>{new Date(sample.doneAt).toLocaleString()}</td>
                    <td>{duration(sample.hours)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </details>
      )}
    </section>
  );
}
