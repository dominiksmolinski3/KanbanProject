import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useKanban } from '../context/KanbanContext';
import { fetchFlowMetrics } from '../services/flowApi';
import '../styles/components/FlowMetrics.css';

/** The windows offered; all inside the server's 180-day limit, which it refuses rather than clamps. */
const WINDOWS = [14, 30, 90, 180];

/** Eight validated categorical slots (see FlowMetrics.css); a ninth column folds into "Other". */
const MAX_SERIES = 8;

const WIDTH = 720;
const HEIGHT = 240;
const MARGIN = { top: 12, right: 12, bottom: 28, left: 40 };
const PLOT_W = WIDTH - MARGIN.left - MARGIN.right;
const PLOT_H = HEIGHT - MARGIN.top - MARGIN.bottom;

const isoDate = (date) => date.toISOString().slice(0, 10);

/** A round step giving three or four gridlines, so the axis reads without competing with the data. */
function niceMax(value) {
  if (value <= 4) return Math.max(1, value);
  const step = Math.pow(10, Math.floor(Math.log10(value)));
  const candidates = [1, 2, 2.5, 5, 10].map(m => m * step);
  const unit = candidates.find(c => value / c <= 4) || step * 10;
  return Math.ceil(value / unit) * unit;
}

function useDuration() {
  const { t } = useTranslation();
  return useCallback((hours) => {
    if (hours === null || hours === undefined) return t('flow.none');
    if (hours < 48) return t('flow.hours', { count: Math.round(hours * 10) / 10 });
    return t('flow.days', { count: Math.round(hours / 2.4) / 10 });
  }, [t]);
}

/**
 * Where a tooltip sits: right of the point in the left half of the plot and left of it in the right
 * half, so a point at either edge never pushes its tooltip off the card.
 */
const tooltipAt = (px) => {
  const left = (px / WIDTH) * 100;
  return { left: `${left}%`, transform: left > 50 ? 'translateX(calc(-100% - 12px))' : 'translateX(12px)' };
};

const shortDate = (iso) => new Date(`${iso}T00:00:00`).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });

/**
 * The board's flow (FEAT-07): a cumulative flow diagram, the cycle times of what finished, and
 * throughput - all read from `task_column_history`, which the server has recorded on every move
 * all along and which, until now, only the task panel read, one card at a time.
 *
 * Every chart has a table beside it: three of the eight band colours sit under 3:1 against the
 * light surface, and a table is the relief that makes the numbers readable without the colour.
 */
function FlowMetrics() {
  const { activeBoardId } = useKanban();
  const { t } = useTranslation();
  const duration = useDuration();

  const [days, setDays] = useState(30);
  const [start, setStart] = useState('');
  const [done, setDone] = useState('');
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);

  // Only the newest request may draw. Changing two controls quickly sends two requests, and the
  // older one arriving last would otherwise show numbers for a choice nobody can see any more -
  // the race TaskSearch guards the same way.
  const latest = useRef(0);

  const load = useCallback(async () => {
    const request = ++latest.current;
    setLoading(true);
    setFailed(false);
    try {
      const to = new Date();
      const from = new Date(to);
      from.setDate(from.getDate() - (days - 1));
      const result = await fetchFlowMetrics({
        boardId: activeBoardId, from: isoDate(from), to: isoDate(to), start, done
      });
      if (request === latest.current) {
        setData(result);
      }
    } catch (error) {
      if (request === latest.current) {
        console.error('Error fetching the flow metrics:', error);
        setFailed(true);
        setData(null);
      }
    } finally {
      if (request === latest.current) {
        setLoading(false);
      }
    }
  }, [activeBoardId, days, start, done]);

  useEffect(() => {
    load();
  }, [load]);

  // A column id means nothing on another board, and the server would refuse it with a 400. Only a
  // real switch clears the choice: the board resolving for the first time is not one, and treating
  // it as one threw away a column somebody had already picked.
  const shownBoard = useRef(activeBoardId);
  useEffect(() => {
    if (shownBoard.current !== null && shownBoard.current !== undefined && shownBoard.current !== activeBoardId) {
      setStart('');
      setDone('');
    }
    shownBoard.current = activeBoardId;
  }, [activeBoardId]);

  const columns = data?.columns || [];

  return (
    <div className="flow-metrics viz-root">
      <div className="flow-header">
        <h2>{t('flow.heading')}</h2>
        <p className="flow-explainer">{t('flow.explainer')}</p>
      </div>

      <div className="flow-filters">
        <label>
          <span>{t('flow.window')}</span>
          <select value={days} onChange={(event) => setDays(Number(event.target.value))}>
            {WINDOWS.map(n => (
              <option key={n} value={n}>{t('flow.lastDays', { count: n })}</option>
            ))}
          </select>
        </label>
        <label>
          <span>{t('flow.startsAt')}</span>
          <select value={start} onChange={(event) => setStart(event.target.value)}>
            <option value="">{t('flow.arrivalOnBoard')}</option>
            {columns.map(column => (
              <option key={column.id} value={column.id}>{column.name}</option>
            ))}
          </select>
        </label>
        <label>
          <span>{t('flow.doneAt')}</span>
          <select value={done} onChange={(event) => setDone(event.target.value)}>
            <option value="">{t('flow.lastColumn')}</option>
            {columns.map(column => (
              <option key={column.id} value={column.id}>{column.name}</option>
            ))}
          </select>
        </label>
      </div>

      {loading && <p className="flow-empty">{t('flow.loading')}</p>}
      {!loading && failed && <p className="flow-empty">{t('flow.failed')}</p>}

      {!loading && data && (
        <>
          <div className="flow-tiles">
            <div className="flow-tile">
              <span className="flow-tile-label">{t('flow.finished')}</span>
              <span className="flow-tile-value">{data.cycleTime.count}</span>
            </div>
            <div className="flow-tile">
              <span className="flow-tile-label">{t('flow.median')}</span>
              <span className="flow-tile-value">{duration(data.cycleTime.medianHours)}</span>
            </div>
            <div className="flow-tile">
              <span className="flow-tile-label">{t('flow.p85')}</span>
              <span className="flow-tile-value">{duration(data.cycleTime.p85Hours)}</span>
            </div>
            <div className="flow-tile">
              <span className="flow-tile-label">{t('flow.average')}</span>
              <span className="flow-tile-value">{duration(data.cycleTime.averageHours)}</span>
            </div>
          </div>

          <CumulativeFlow data={data} />
          <CycleTimes data={data} duration={duration} />
          <Throughput data={data} />
        </>
      )}
    </div>
  );
}

// ------------------------------------------------------------------ cumulative flow ---

/**
 * The series the chart draws, in board order. Past eight columns the earliest fold into one
 * "Other" band - a ninth generated hue would be indistinguishable from its neighbours - and the
 * columns people watch most (the later ones, nearest done) keep their own colours.
 */
function useSeries(data) {
  const { t } = useTranslation();
  return useMemo(() => {
    const columns = data.columns;
    const fold = Math.max(0, columns.length - (MAX_SERIES - 1));
    const series = [];
    if (columns.length > MAX_SERIES) {
      series.push({ key: 'other', name: t('flow.otherColumns'), indices: columns.slice(0, fold).map((_, i) => i) });
      columns.slice(fold).forEach((column, i) => series.push({ key: column.id, name: column.name, indices: [fold + i] }));
    } else {
      columns.forEach((column, i) => series.push({ key: column.id, name: column.name, indices: [i] }));
    }
    return series.map((s, slot) => ({ ...s, slot: slot + 1 }));
  }, [data.columns, t]);
}

function CumulativeFlow({ data }) {
  const { t } = useTranslation();
  const series = useSeries(data);
  const [hover, setHover] = useState(null);
  const days = data.cumulativeFlow;

  const values = days.map(day => series.map(s => s.indices.reduce((sum, i) => sum + (day.counts[i] || 0), 0)));
  const max = niceMax(Math.max(0, ...values.map(v => v.reduce((a, b) => a + b, 0))));
  const x = (i) => MARGIN.left + (days.length === 1 ? PLOT_W / 2 : (i / (days.length - 1)) * PLOT_W);
  const y = (v) => MARGIN.top + PLOT_H - (v / max) * PLOT_H;

  // Stacked from the bottom in reverse board order, so done sits on the baseline and the backlog
  // on top - the conventional reading, where the band widths are the work in each stage.
  const stacked = [...series].reverse();
  const bands = [];
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

  const onMove = (event) => {
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
          <div className="flow-tooltip" style={tooltipAt(x(hover))}>
            <strong>{shortDate(days[hover].date)}</strong>
            {series.map((s, i) => (
              <div key={s.key} className="flow-tooltip-row">
                <span className={`flow-swatch slot-${s.slot}`} />
                <span>{s.name}</span>
                <span className="flow-tooltip-value">{values[hover][i]}</span>
              </div>
            ))}
          </div>
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

/** `counts` keeps every gridline on a whole number - half a card is not a quantity anybody has. */
function Axis({ max, y, days, x, counts = false, format = (v) => Math.round(v * 10) / 10 }) {
  const ticks = [...new Set(counts ? [0, Math.round(max / 2), max] : [0, max / 2, max])];
  const labelled = days.length > 2 ? [0, Math.floor((days.length - 1) / 2), days.length - 1] : days.map((_, i) => i);
  return (
    <g className="flow-axis">
      {ticks.map(v => (
        <g key={v}>
          <line x1={MARGIN.left} x2={WIDTH - MARGIN.right} y1={y(v)} y2={y(v)} className="flow-grid" />
          <text x={MARGIN.left - 6} y={y(v)} dy="0.32em" textAnchor="end">{format(v)}</text>
        </g>
      ))}
      {labelled.map(i => (
        <text key={i} x={x(i)} y={HEIGHT - 8} textAnchor={i === 0 ? 'start' : i === days.length - 1 ? 'end' : 'middle'}>
          {shortDate(days[i].date)}
        </text>
      ))}
    </g>
  );
}

// ------------------------------------------------------------------ cycle time ---

function CycleTimes({ data, duration }) {
  const { t } = useTranslation();
  const [hover, setHover] = useState(null);
  const samples = data.samples;
  const days = data.cumulativeFlow;
  const first = new Date(`${data.from}T00:00:00`).getTime();
  const span = new Date(`${data.to}T00:00:00`).getTime() + 86400000 - first;
  const max = niceMax(Math.max(1, ...samples.map(s => s.hours)));
  const x = (when) => MARGIN.left + ((new Date(when).getTime() - first) / span) * PLOT_W;
  const y = (h) => MARGIN.top + PLOT_H - (h / max) * PLOT_H;
  const dayX = (i) => MARGIN.left + (days.length === 1 ? PLOT_W / 2 : (i / (days.length - 1)) * PLOT_W);
  const { medianHours, p85Hours } = data.cycleTime;

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
            {[['median', medianHours], ['p85', p85Hours]].map(([key, value]) => value !== null && (
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
            <div className="flow-tooltip" style={tooltipAt(x(samples[hover].doneAt))}>
              <strong>{samples[hover].title}</strong>
              <div className="flow-tooltip-row">
                <span>{t('flow.took')}</span>
                <span className="flow-tooltip-value">{duration(samples[hover].hours)}</span>
              </div>
              <div className="flow-tooltip-row">
                <span>{t('flow.finishedOn')}</span>
                <span className="flow-tooltip-value">{new Date(samples[hover].doneAt).toLocaleString()}</span>
              </div>
            </div>
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

// ------------------------------------------------------------------ throughput ---

function Throughput({ data }) {
  const { t } = useTranslation();
  const [hover, setHover] = useState(null);
  const days = data.throughput;
  const max = niceMax(Math.max(1, ...days.map(d => d.count)));
  const slot = PLOT_W / days.length;
  const barW = Math.max(2, slot - 2);
  const x = (i) => MARGIN.left + i * slot + (slot - barW) / 2;
  const y = (v) => MARGIN.top + PLOT_H - (v / max) * PLOT_H;
  const center = (i) => MARGIN.left + i * slot + slot / 2;
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
          <div className="flow-tooltip" style={tooltipAt(center(hover))}>
            <strong>{shortDate(days[hover].date)}</strong>
            <div className="flow-tooltip-row">
              <span>{t('flow.finished')}</span>
              <span className="flow-tooltip-value">{days[hover].count}</span>
            </div>
          </div>
        )}
      </div>
    </section>
  );
}

export default FlowMetrics;
