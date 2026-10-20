const pad = (n: number) => String(n).padStart(2, '0');

export function toInstant(localValue: string | null | undefined): string | null {
  if (!localValue) return null;
  const date = new Date(localValue);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

export function toLocalInput(instant: string | null | undefined): string {
  if (!instant) return '';
  const date = new Date(instant);
  if (Number.isNaN(date.getTime())) return '';
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
    + `T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}
