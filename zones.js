/**
 * Подбор зоны отпуска по требуемой твёрдости.
 * Зона: tFrom/tTo (°C) ↔ hi/lo (твёрдость): hi соответствует tFrom, lo — tTo.
 * Внутри зоны — линейная интерполяция.
 */
export const zoneTemp = (z, h) => z.tFrom + ((z.hi - h) / (z.hi - z.lo)) * (z.tTo - z.tFrom);

const BRIDGE_MAX_GAP = 2; // макс. «дыра» между зонами (ед. твёрдости), которую допустимо интерполировать

export function pickZone(zones, h, unit) {
  const zs = zones.filter((z) => z.hi != null && (z.unit || 'HRC') === unit);
  const allowed = zs.filter((z) => z.status !== 'forbidden');

  // 1) попадание в зону; при перекрытии — по приоритету
  const inside = allowed.filter((z) => h >= z.lo && h <= z.hi);
  if (inside.length) {
    const zone = [...inside].sort((a, b) => (b.priority ?? 2) - (a.priority ?? 2))[0];
    return { type: 'zone', zone, temp: zoneTemp(zone, h) };
  }

  // 2) твёрдость достигается только в запрещённой зоне
  const bad = zs.find((z) => z.status === 'forbidden' && h >= z.lo && h <= z.hi);
  if (bad) return { type: 'forbidden', zone: bad, ...neighbours(allowed, h) };

  // 3) небольшая «дыра» между соседними зонами — интерполяция через границу
  const n = neighbours(allowed, h);
  if (n.above && n.below && n.above.lo - n.below.hi <= BRIDGE_MAX_GAP) {
    const a = n.above;
    const b = n.below;
    const tA = a.tTo; // температура при a.lo
    const tB = b.tFrom; // температура при b.hi
    const k = (a.lo - h) / (a.lo - b.hi);
    return { type: 'bridge', zoneA: a, zoneB: b, temp: tA + k * (tB - tA) };
  }

  return { type: 'out', ...n };
}

function neighbours(allowed, h) {
  const above = allowed.filter((z) => z.lo > h).sort((a, b) => a.lo - b.lo)[0] || null;
  const below = allowed.filter((z) => z.hi < h).sort((a, b) => b.hi - a.hi)[0] || null;
  return { above, below };
}
