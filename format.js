/** 103 -> "1 ч 43 мин (103 мин)" */
export function fmtMin(m) {
  const t = Math.ceil(m);
  if (t < 60) return `${t} мин`;
  const h = Math.floor(t / 60);
  const mm = t % 60;
  return `${h} ч ${String(mm).padStart(2, '0')} мин (${t} мин)`;
}
export const fmtRange = (a, b, unit = '') => `${a}–${b}${unit ? ' ' + unit : ''}`;
