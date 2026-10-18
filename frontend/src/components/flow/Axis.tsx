import { HEIGHT, MARGIN, WIDTH, shortDate } from './chartGeometry';

interface AxisProps {
  max: number;
  y: (value: number) => number;
  days: { date: string }[];
  x: (index: number) => number;
  counts?: boolean;
  format?: (value: number) => string | number;
}

export default function Axis({ max, y, days, x, counts = false, format = (v) => Math.round(v * 10) / 10 }: AxisProps) {
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
