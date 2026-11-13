export const SHAPES = ['Вал', 'Плита', 'Кольцо', 'Втулка'];
export const isHollow = (shape) => shape === 'Кольцо' || shape === 'Втулка';

/** Толщина стенки кольца/втулки: (Dнар − dвн) / 2 */
export const wallOf = (outer, inner) => (outer - inner) / 2;

export function sizeLabels(shape) {
  if (shape === 'Плита') return { size: 'Толщина H, мм' };
  if (isHollow(shape)) return { size: 'Наружный Ø, мм', inner: 'Внутренний Ø, мм' };
  return { size: 'Наружный Ø D, мм' };
}

/** Общая проверка геометрии. Возвращает текст ошибки или null. */
export function validateGeometry({ shape, size, inner }) {
  if (!(size > 0)) return 'Введите размер больше 0 мм';
  if (isHollow(shape)) {
    if (!(inner >= 0)) return 'Введите внутренний диаметр';
    if (inner >= size) return 'Внутренний Ø должен быть меньше наружного';
  }
  return null;
}
