const PWHT_MM_PER_HOUR = 25;

export function calcWelding(steel, thickness) {
  const w = steel.weld;
  const result = { electrode: w.electrode, wire: w.wire, preheat: w.preheat, note: w.note, pwht: null };

  if (w.pwht) {
    result.pwht = {
      tMin: w.pwht.tMin,
      tMax: w.pwht.tMax,
      required: w.pwht.required,
      holdHours: Math.max(1, Math.ceil(thickness / PWHT_MM_PER_HOUR)),
      cooling: 'медленно (до 50 °C/ч), с печью',
    };
  }
  return result;
}
