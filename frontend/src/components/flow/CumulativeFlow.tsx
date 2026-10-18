import { useMemo, useState, type MouseEvent } from 'react';
import { useTranslation } from 'react-i18next';
import type { FlowMetrics } from '../../types/api';
import Axis from './Axis';
import { ChartTooltip, TooltipRow } from './ChartTooltip';
import { HEIGHT, MARGIN, PLOT_H, PLOT_W, WIDTH, niceMax, shortDate } from './chartGeometry';

const MAX_SERIES = 8;

interface Series {
  key: string | number;
  name: string;
  indices: number[];
  slot: number;
}

function useSeries(columns: FlowMetrics['columns']): Series[] {
  const { t } = useTranslation();
  return useMemo(() => {
    const fold = Math.max(0, columns.length - (MAX_SERIES - 1));
    const series: Omit<Series, 'slot'>[] = [];
    if (columns.length > MAX_SERIES) {
      series.push({ key: 'other', name: t('flow.otherColumns'), indices: columns.slice(0, fold).map((_, i) => i) });
      columns.slice(fold).forEach((column, i) => series.push({ key: column.id, name: column.name, indices: [fold + i] }));
    } else {
      columns.forEach((column, i) => series.push({ key: column.id, name: column.name, indices: [i] }));
    }
    return series.map((s, slot) => ({ ...s, slot: slot + 1 }));
  }, [columns, t]);
}

export default function CumulativeFlow({ data }: { data: FlowMetrics }) {
  const { t } = useTranslation();
  const series = useSeries(data.columns);
  const [hover, setHover] = useState<number | null>(null);
  const days = data.cumulativeFlow;

  const values = days.map(day => series.map(s => s.indices.reduce((sum, i) => sum + (day.counts[i] || 0), 0)));
  const max = niceMax(Math.max(0, ...values.map(v => v.reduce((a, b) => a + b, 0))));
  const x = (i: number) => MARGIN.left + (days.length === 1 ? PLOT_W / 2 : (i / (days.length - 1)) * PLOT_W);
  const y = (v: number) => MARGIN.top + PLOT_H - (v / max) * PLOT_H;

  const stacked = [...series].reverse();
  const bands: { s: Series; path: string }[] = [];
  const base = days.map(() => 0);
  stacked.forEach(s => {
    const idx = series.indexOf(s);
    const lower = [...base];
    days.forEach((_, d) => { base[d] += values[d][idx]; });
    const upper = [...base];
    const top = upper.map((v, d) => `${x(d)},${y(v)}`).join(' L');
    const bottom = lower.map((v, d) => `${x(d)},${y(v)}`).reverse().join(' L');
    bands.push({ s, path: `M${top} L${bottom} Z` });
  });

  const onMove = (event: MouseEvent<SVGRectElement>) => {
    const box = event.currentTarget.getBoundingClientRect();
    const px = ((event.clientX - box.left) / box.width) * WIDTH;
    const i = Math.round(((px - MARGIN.left) / PLOT_W) * (days.length - 1));
    setHover(Math.max(0, Math.min(days.length - 1, i)));
  };

  if (series.length === 0) {
    return <p className="flow-empty">{t('flow.noColumns')}</p>;
  }

  return (
    <section className="flow-card">
      <h3>{t('flow.cumulativeFlow')}</h3>
      <p className="flow-caption">{t('flow.cumulativeFlowCaption')}</p>
      <ul className="flow-legend">
        {series.map(s => (
          <li key={s.key}><span className={`flow-swatch slot-${s.slot}`} />{s.name}</li>
        ))}
      </ul>
      <div className="flow-chart">
        <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} role="img" aria-label={t('flow.cumulativeFlow')}>
          <Axis max={max} y={y} days={days} x={x} counts />
          {bands.map(({ s, path }) => (
            <path key={s.key} d={path} className={`flow-band slot-${s.slot}`} />
          ))}
          {hover !== null && (
            <line className="flow-crosshair" x1={x(hover)} x2={x(hover)} y1={MARGIN.top} y2={MARGIN.top + PLOT_H} />
          )}
          <rect className="flow-hit" x={MARGIN.left} y={MARGIN.top} width={PLOT_W} height={PLOT_H}
            onMouseMove={onMove} onMouseLeave={() => setHover(null)} />
        </svg>
        {hover !== null && (
          <ChartTooltip at={x(hover)} heading={shortDate(days[hover].date)}>
            {series.map((s, i) => (
              <TooltipRow key={s.key} slot={s.slot} label={s.name} value={values[hover][i]} />
            ))}
          </ChartTooltip>
        )}
      </div>
      <details className="flow-table">
        <summary>{t('flow.showTable')}</summary>
        <div className="flow-table-scroll">
          <table>
            <thead>
              <tr>
                <th scope="col">{t('flow.date')}</th>
                {series.map(s => <th key={s.key} scope="col">{s.name}</th>)}
              </tr>
            </thead>
            <tbody>
              {days.map((day, d) => (
                <tr key={day.date}>
                  <td>{shortDate(day.date)}</td>
                  {values[d].map((v, i) => <td key={series[i].key}>{v}</td>)}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </section>
  );
}
