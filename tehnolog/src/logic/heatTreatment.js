import { tempForHardness, roundTo } from './interpolate';

export const SHAPES = ['Вал', 'Плита', 'Кольцо', 'Втулка'];

const HOLD_MIN_PER_MM = 1.5; // мин на 1 мм сечения
// Задел под поправку на форму. Пока везде 1.0 — уточнить по своей ТИ.
const SHAPE_FACTOR = { Вал: 1, Плита: 1, Кольцо: 1, Втулка: 1 };

export function calcHeatTreatment(steel, { shape, size, hrc }) {
  const result = { gost: steel.gost, quench: null, temper: null, note: steel.heatNote || null };

  if (steel.quench) {
    const raw = size * HOLD_MIN_PER_MM * (SHAPE_FACTOR[shape] ?? 1);
    result.quench = {
      tMin: steel.quench.tMin,
      tMax: steel.quench.tMax,
      medium: steel.quench.medium,
      holdMin: Math.ceil(raw / 5) * 5, // округление вверх до 5 мин
    };
  }

  if (steel.temper) {
    const { temp, clamped, range } = tempForHardness(steel.temper.curve, hrc);
    result.temper = {
      temp: roundTo(temp, 5),
      cooling: steel.temper.cooling,
      warning: clamped
        ? `Твёрдость ${hrc} HRC вне диапазона данных (${range[0]}–${range[1]} HRC) — взято крайнее значение.`
        : null,
    };
  }
  return result;
}
