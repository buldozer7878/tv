import { tempForHardness, roundTo } from '../interpolate';
import { wallOf, isHollow } from '../shape';

const HOLD_MIN_PER_MM = 1.5;

/** Справочная модель для марок без ТИ (прежняя логика: 1,5 мин/мм, кривая T–HRC). */
export function calcReference(steel, { shape, size, inner, hrc }) {
  const s = isHollow(shape) ? wallOf(size, inner) : size;
  const blocks = [];
  const alerts = [];

  if (steel.quench) {
    const hold = Math.ceil((s * HOLD_MIN_PER_MM) / 5) * 5;
    blocks.push({
      label: 'Закалка',
      text: `Нагрев ${steel.quench.tMin}–${steel.quench.tMax} °C, среда ${steel.quench.medium}, выдержка ~ ${hold} мин (1,5 мин на 1 мм сечения, s = ${s} мм).`,
    });
  }
  if (steel.temper) {
    const { temp, clamped, range } = tempForHardness(steel.temper.curve, hrc);
    blocks.push({
      label: 'Отпуск',
      text: `Нагрев ${roundTo(temp, 5)} °C, охлаждение ${steel.temper.cooling}.`,
      warning: clamped ? `Твёрдость ${hrc} HRC вне диапазона данных (${range[0]}–${range[1]} HRC) — взято крайнее значение.` : null,
    });
  }
  if (steel.heatNote) blocks.push({ label: 'Примечание', text: steel.heatNote });
  return { gost: steel.gost, blocks, alerts };
}
