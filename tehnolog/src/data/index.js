import steels from './steels.json';
import standards from './standards.json';

export { steels, standards };
export const DEFAULT_STEEL_ID = 's40khn2ma';
export const getSteelById = (id) => steels.find((s) => s.id === id);
export const steelLabel = (s) => `${s.name} (${s.gost})`;
