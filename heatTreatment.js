import { calcReference } from './models/reference';
import { calcTi40khn } from './models/ti40khn';
import { calcTi20kh13 } from './models/ti20kh13';
import { validateGeometry } from './shape';

export { SHAPES } from './shape';

/** input: { shape, size, inner, hole, stacking, hrc, unit } */
export function calcHeatTreatment(steel, input) {
  const err = validateGeometry(input);
  if (err) return { error: err };
  if (!(input.hrc > 0)) return { error: 'Введите требуемую твёрдость' };

  const out =
    steel.model === 'ti40khn' ? calcTi40khn(steel, input)
    : steel.model === 'ti20kh13' ? calcTi20kh13(steel, input)
    : calcReference(steel, input);
  return out;
}
