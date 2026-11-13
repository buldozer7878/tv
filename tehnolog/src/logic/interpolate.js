/**
 * Температура отпуска по требуемой твёрдости.
 * curve: [[T_°C, HRC], ...] — линейная интерполяция между соседними точками.
 * Вне диапазона значение ограничивается краем кривой (clamped = true).
 */
export function tempForHardness(curve, hrc) {
  const pts = [...curve].sort((a, b) => a[1] - b[1]); // по HRC по возрастанию
  const minHrc = pts[0][1];
  const maxHrc = pts[pts.length - 1][1];

  if (hrc <= minHrc) return { temp: pts[0][0], clamped: hrc < minHrc, range: [minHrc, maxHrc] };
  if (hrc >= maxHrc) return { temp: pts[pts.length - 1][0], clamped: hrc > maxHrc, range: [minHrc, maxHrc] };

  for (let i = 0; i < pts.length - 1; i++) {
    const [t1, h1] = pts[i];
    const [t2, h2] = pts[i + 1];
    if (hrc >= h1 && hrc <= h2) {
      const k = (hrc - h1) / (h2 - h1);
      return { temp: t1 + k * (t2 - t1), clamped: false, range: [minHrc, maxHrc] };
    }
  }
  return { temp: pts[0][0], clamped: true, range: [minHrc, maxHrc] };
}

export const roundTo = (value, step) => Math.round(value / step) * step;
