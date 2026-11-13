import { pickZone } from '../zones';
import { fmtMin } from '../format';
import { roundTo } from '../interpolate';
import { wallOf, isHollow } from '../shape';

/** Расчётный размер s по разд. 5.1 РТМ 20Х13 */
export function designSize({ shape, size, inner, hole }) {
  if (isHollow(shape)) return hole === 'blind' ? size : wallOf(size, inner);
  return size; // вал — D, плита — H
}

export function timeFor(kind, s) {
  const base = 1.5 * s;
  if (kind === 'quench') return base + 30;
  if (kind === 'low') return base + (s <= 50 ? 60 : 90);
  if (kind === 'high') return base + 90;
  if (kind === 'anneal') return base + (s <= 50 ? 90 : s <= 80 ? 120 : s <= 100 ? 135 : 150);
  return base + 90;
}

export function calcTi20kh13(steel, input) {
  const { shape, size, inner, hole, hrc, unit } = input;
  const s = designSize({ shape, size, inner, hole });
  const blocks = [];
  const alerts = [];

  if (isHollow(shape) && hole === 'blind') alerts.push('Глухое отверстие: s принят как для сплошного тела (s = Dнар).');
  alerts.push('Теплопроводность 20Х13 в 1,5–2 раза ниже, чем у углеродистых сталей: быстрый нагрев массивных сечений вызывает трещины. Допуск печи ±10 °C.');

  // ---- Закалка ----
  const pre =
    s > 50 ? `Предварительный подогрев 650–750 °C обязателен: T = (0,5…1,0)·s = ${Math.ceil(0.5 * s)}–${Math.ceil(s)} мин (в закалку не входит).`
    : s > 40 ? `Подогрев 650–750 °C по необходимости (s > 40–50 мм или сложная форма): ${Math.ceil(0.5 * s)}–${Math.ceil(s)} мин.`
    : 'Подогрев для простой формы не требуется.';
  blocks.push({
    label: 'Закалка',
    text:
      `Расчётный размер s = ${Math.round(s * 10) / 10} мм.\n` +
      `Нагрев 1000–1050 °C (оптимально 1010–1030), охлаждение в масле, твёрдость 45–50 HRC.\n` +
      `Время после выхода печи на уставку: T = 1,5·s + 30 = ${fmtMin(timeFor('quench', s))}.\n` +
      pre,
  });

  // ---- Отпуск / отжиг ----
  const res = pickZone(steel.temperZones, hrc, unit);
  const tb = { label: 'Отпуск', text: '', warning: null };

  if (res.type === 'zone' || res.type === 'bridge') {
    const z = res.type === 'zone' ? res.zone : res.zoneA;
    let temp = roundTo(res.temp, 5);
    if (z.maxSetpoint && temp > z.maxSetpoint) temp = z.maxSetpoint;
    tb.label = z.kind === 'anneal' ? 'Отжиг' : 'Отпуск';

    const lines = [];
    lines.push(`Нагрев ${temp} °C — ${res.type === 'zone' ? `зона ${z.tFrom}–${z.tTo} °C: ${z.label}` : `между зонами ${res.zoneB.tFrom}–${res.zoneB.tTo} и ${res.zoneA.tFrom}–${res.zoneA.tTo} °C`}.`);
    lines.push(`Время после выхода печи на уставку: ${fmtMin(timeFor(z.timing, s))}.`);
    lines.push(`Охлаждение: ${z.cooling}.`);
    if (z.kind !== 'anneal') lines.push('Выгружать из печи сразу; оставлять в печи и охлаждать с печью запрещено (п. 3.4). Погружение в воду запрещено.');
    lines.push(`Контроль ОТК: ${z.control || unit}.`);
    if (z.note) lines.push(z.note);
    if (z.timeNote) lines.push(z.timeNote);
    tb.text = lines.join('\n');

    const w = [];
    if (z.status === 'restricted') w.push(`Только по письменному разрешению главного металлурга (${z.permit}).`);
    if (res.type === 'bridge') w.push('Твёрдость лежит между режимами РТМ — температура получена интерполяцией.');
    if (z.verify) w.push('Температуру уточнять по образцам-свидетелям (п. 8.5): пробный отпуск на 2–3 температурах, шаг 10–15 °C, результат в журнал.');
    if (z.furnaceTol) w.push(`Печь с допуском не хуже ±${z.furnaceTol} °C: колебание ±10 °C выводит твёрдость за допуск.`);
    if (z.maxSetpoint) w.push(`Уставка не выше ${z.maxSetpoint} °C (потолок 780 °C с учётом допуска ±15 °C).`);
    tb.warning = w.join('\n') || null;
  } else if (res.type === 'forbidden') {
    tb.text = `Режим запрещён (${res.zone.label}).`;
    tb.warning = 'Режим не назначен.';
  } else {
    let hint;
    if (unit === 'HRC' && hrc < 24) hint = 'Ниже 24 HRC (≈ 241 HB) твёрдость контролируют по Бринеллю — переключите шкалу на HB (РТМ п. 8.4: ниже 226 HB по HRC измерять запрещено).';
    else if (unit === 'HRC' && hrc > 47) hint = 'После закалки 45–50 HRC; низкий отпуск 200–280 °C даёт 42–47 HRC.';
    else if (unit === 'HB' && hrc > 290) hint = 'Выше 290 HB твёрдость задаётся по HRC — переключите шкалу на HRC.';
    else if (unit === 'HB' && hrc < 150) hint = 'Ниже 150 HB в РТМ режимов нет (отжиг даёт 150–197 HB).';
    else hint = 'Между режимами РТМ: задайте значение из карты режимов.';
    tb.text = `${hrc} ${unit} вне режимов РТМ 20Х13. ${hint}`;
    tb.warning = 'Режим не назначен.';
  }
  blocks.push(tb);

  blocks.push({
    label: 'Обязательно',
    text:
      'Детали на поддоне в один слой, зазоры 10–20 мм; засыпка навалом запрещена.\n' +
      'Образцы-свидетели: не менее 2 на садку (та же плавка и сечение); контроль не менее 10 % деталей садки (мин. 3, по 3 замера).\n' +
      'При нагреве выше 750 °C — защита чистовых поверхностей от окалины.',
  });

  return { gost: steel.gost, blocks, alerts };
}
