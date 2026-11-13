import { equivDiameter, temperTime } from './models/ti40khn';
import { timeFor } from './models/ti20kh13';
import { fmtMin } from './format';

const PWHT_MM_PER_HOUR = 25;

function pwhtHold(steel, p, t) {
  if (p.timing === 'ti' && steel.model === 'ti40khn') {
    const d = equivDiameter({ shape: 'Плита', size: t });
    return `выдержка по ТИ-40ХН (Dэкв = 1,5·H = ${d} мм): ${fmtMin(temperTime('mid', d, 1).total)}`;
  }
  if (p.timing === 'ti' && steel.model === 'ti20kh13') {
    return `выдержка по РТМ 20Х13 (s = ${t} мм): ${fmtMin(timeFor('high', t))}`;
  }
  const h = Math.max(1, Math.ceil(t / PWHT_MM_PER_HOUR));
  return `выдержка ~${h} ч (1 ч на 25 мм, минимум 1 ч)`;
}

export function calcWelding(steel, thickness) {
  const w = steel.weld;
  const result = { electrode: w.electrode, wire: w.wire, preheat: w.preheat, note: w.note, pwht: null };
  if (w.pwht) {
    result.pwht = {
      tMin: w.pwht.tMin,
      tMax: w.pwht.tMax,
      required: w.pwht.required,
      hold: pwhtHold(steel, w.pwht, thickness),
      cooling: w.pwht.cooling,
    };
  }
  return result;
}
