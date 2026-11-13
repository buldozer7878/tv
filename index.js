import steels from './steels.json';
import standards from './standards.json';

export { steels, standards };
export const DEFAULT_STEEL_ID = 's40khn2ma';
export const getSteelById = (id) => steels.find((s) => s.id === id);
export const steelLabel = (s) => `${s.name} (${s.gost})`;

export const isTi = (s) => s.source === 'ti';
export const sourceLabel = (s) =>
  isTi(s) ? `Источник: ${s.docRef}` : 'Справочные данные — НЕ из ваших ТИ, сверить перед применением';

/** Диапазон температур отпуска для краткой сводки (без запрещённых зон). */
export function temperRange(s) {
  if (s.temperZones) {
    const z = s.temperZones.filter((x) => x.status !== 'forbidden');
    return [Math.min(...z.map((x) => x.tFrom)), Math.max(...z.map((x) => x.tTo))];
  }
  if (s.temper) {
    const t = s.temper.curve.map((p) => p[0]);
    return [Math.min(...t), Math.max(...t)];
  }
  return null;
}
