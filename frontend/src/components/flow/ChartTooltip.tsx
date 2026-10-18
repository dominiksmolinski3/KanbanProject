import type { ReactNode } from 'react';
import { tooltipAt } from './chartGeometry';

export function ChartTooltip({ at, heading, children }: { at: number; heading: ReactNode; children: ReactNode }) {
  return (
    <div className="flow-tooltip" style={tooltipAt(at)}>
      <strong>{heading}</strong>
      {children}
    </div>
  );
}

export function TooltipRow({ label, value, slot }: { label: ReactNode; value: ReactNode; slot?: number }) {
  return (
    <div className="flow-tooltip-row">
      {slot !== undefined && <span className={`flow-swatch slot-${slot}`} />}
      <span>{label}</span>
      <span className="flow-tooltip-value">{value}</span>
    </div>
  );
}
