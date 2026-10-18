import type { CSSProperties } from 'react';

export const WIDTH = 720;
export const HEIGHT = 240;
export const MARGIN = { top: 12, right: 12, bottom: 28, left: 40 };
export const PLOT_W = WIDTH - MARGIN.left - MARGIN.right;
export const PLOT_H = HEIGHT - MARGIN.top - MARGIN.bottom;

export function niceMax(value: number): number {
  if (value <= 4) return Math.max(1, value);
  const step = Math.pow(10, Math.floor(Math.log10(value)));
  const candidates = [1, 2, 2.5, 5, 10].map(m => m * step);
  const unit = candidates.find(c => value / c <= 4) || step * 10;
  return Math.ceil(value / unit) * unit;
}

export const tooltipAt = (px: number): CSSProperties => {
  const left = (px / WIDTH) * 100;
  return { left: `${left}%`, transform: left > 50 ? 'translateX(calc(-100% - 12px))' : 'translateX(12px)' };
};

export const shortDate = (iso: string): string =>
  new Date(`${iso}T00:00:00`).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
