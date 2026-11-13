import { pickZone } from '../zones';
import { fmtMin } from '../format';
import { roundTo } from '../interpolate';
import { wallOf, isHollow } from '../shape';

export const STACKING = [
  { id: 'single', label: 'Одиночная', k: 1.0 },
  { id: 'row', label: 'Ряд', k: 1.3 },
  { id: 'multi', label: 'Слои', k: 1.5 },
];

/** Dэкв по разд. 5.4 ТИ-40ХН */
export function equivDiameter({ shape, size, inner, hole }) {
  if (shape === 'Вал') return size;
  if (shape === 'Плита') return size * 1.5;
  return wallOf(size, inner) * (hole === 'narrow' ? 2.0 : 1.5);
}

export function quenchTime(d, k) {
  const heat = 1.0 * d * k;
  const hold = Math.max(15, 0.3 * d);
  const step = d > 50 ? 25 : 0;
  return { heat, hold, step, total: heat + hold + step, totalNoStep: heat + hold };
}

export function temperTime(kind, d, k) {
  const heat = 1.3 * d * k;
  const hold = kind === 'low' ? Math.max(60, 1.0 * d) : Math.max(90, 1.5 * d);
  return { heat, hold, total: heat + hold };
}

function mediaText(d) {
  const L = [];
  if (d <= 25) L.push('Способ Б — подогретое масло И-20А/30А/40А, 40–60 °C, с циркуляцией. Сквозная прокалка; твёрдость пов./центр 51–55 / 45–51 HRC.');
  else if (d <= 35) L.push('Способ Б (масло 40–60 °C) — только неполная прокалка, 49–53 / 35–43 HRC. Лучше Способ Г.');
  if (d >= 25) {
    const conc = d <= 60 ? '8–12 %' : '12–15 %';
    const hrc = d >= 35 ? (d <= 60 ? ' Твёрдость пов./центр 51–55 / 33–41 HRC.' : ' Твёрдость пов./центр 47–52 / 27–34 HRC.') : '';
    L.push(`Способ Г (рекомендуемый) — водный полимер ПАГ ${conc}, ванна 25–40 °C (выше 40 °C запрещено), извлечение при 100–120 °C, на отпуск ≤ 1,5 ч.${hrc}`);
    L.push('Способ В — вода 25–35 °C → масло 40–60 °C (детали с отверстиями, пазами, если нет ванны ПАГ); воду в масло не заносить.');
  }
  if (d >= 45) L.push('Способ А — подогретая вода, прерванная закалка (только гладкие формы без отверстий, пазов, резьбы, углов); извлечение при 150–180 °C; 100 % НК после отпуска.');
  return L.join('\n');
}

export function calcTi40khn(steel, input) {
  const { shape, size, inner, hole, stacking, hrc } = input;
  const d = equivDiameter({ shape, size, inner, hole });
  const k = (STACKING.find((x) => x.id === stacking) || STACKING[0]).k;
  const blocks = [];
  const alerts = [];

  if (d > 120) alerts.push(`Dэкв = ${d} мм больше 120 мм — вне области применения ТИ-40ХН.`);
  if (shape === 'Плита' && size > 80) alerts.push('Толщина плиты/диска больше 80 мм — вне области применения ТИ.');
  if (d >= 50) alerts.push('При Dэкв 50–120 мм сквозная мартенситная прокалка сердцевины не достигается; нормы ГОСТ гарантируются в слое 15–20 мм и на образцах-свидетелях. Если нужно σв ≥ 980 МПа в центре — согласовать замену на 40ХН2МА.');
  if (stacking === 'multi' && d > 0) alerts.push('Укладку «навалом» (Kукл = 2,0) для ответственных деталей применять запрещено.');

  // ---- Закалка ----
  const q = quenchTime(d, k);
  const range = d <= 60 ? '805–835 °C (820 ± 15 по ГОСТ), оптимально для Dэкв ≤ 60 мм' : '835–845 °C (допустимо для Dэкв > 60 мм); выше 845 °C — брак';
  const stepTxt = q.step
    ? `\nСтупенчатый нагрев 450–550 °C, 25–35 мин (в таблице ТИ учтено 25 мин); обязателен при Dэкв > 65 мм, сложной форме и после наплавки, для Dэкв ≤ 60 мм допускается прямая посадка (без ступени — ${fmtMin(q.totalNoStep)}).`
    : '';
  blocks.push({
    label: 'Закалка',
    text:
      `Dэкв = ${Math.round(d * 10) / 10} мм, Kукл = ${k}.\n` +
      `Нагрев ${range}.\n` +
      `Время: набор ${Math.ceil(q.heat)} + выдержка ${Math.ceil(q.hold)}${q.step ? ' + ступень 25' : ''} = ${fmtMin(q.total)}.${stepTxt}\n` +
      `Перенос из печи в среду — не более 5–7 с. Погружать вертикально (диски — на ребро).\n` +
      mediaText(d),
  });

  // ---- Отпуск ----
  const res = pickZone(steel.temperZones, hrc, 'HRC');
  const tb = { label: 'Отпуск', text: '', warning: null };

  if (res.type === 'zone' || res.type === 'bridge') {
    const z = res.type === 'zone' ? res.zone : res.zoneA;
    const temp = roundTo(res.temp, 5);
    const t = temperTime(z.timing, d, k);
    const lines = [];
    lines.push(`Нагрев ${temp} °C — ${res.type === 'zone' ? `зона ${z.tFrom}–${z.tTo} °C: ${z.label}` : `между зонами ${res.zoneB.tFrom}–${res.zoneB.tTo} и ${res.zoneA.tFrom}–${res.zoneA.tTo} °C`}.`);
    lines.push(`Время: прогрев ${Math.ceil(t.heat)} + выдержка ${Math.ceil(t.hold)} = ${fmtMin(t.total)}.`);
    lines.push(`Охлаждение: ${z.cooling}. После отпуска 400–660 °C быстрое охлаждение обязательно — медленное запрещено (п. 3.5).`);
    if (z.note) lines.push(z.note);
    tb.text = lines.join('\n');

    const w = [];
    if (res.type === 'bridge') w.push('Твёрдость лежит между режимами ТИ — температура получена интерполяцией, подтвердить на образце-свидетеле.');
    if (z.status === 'conditional') w.push('УСЛОВНО: порог хрупкости II рода. Выдержка не более 2 ч, охлаждение обязательно в воде/масле.');
    if (z.status === 'interop') w.push('ТОЛЬКО МЕЖОПЕРАЦИОННЫЙ режим — для готовых деталей не применять.');
    if (z.maxHoldMin && t.hold > z.maxHoldMin) w.push(`Расчётная выдержка ${Math.ceil(t.hold)} мин превышает лимит зоны (${z.maxHoldMin} мин) — по ТИ так нельзя; согласовать с гл. металлургом.`);
    tb.warning = w.join('\n') || null;
  } else if (res.type === 'forbidden') {
    const parts = [];
    if (res.above) parts.push(`не ниже ${res.above.lo} HRC (отпуск ${res.above.tFrom}–${res.above.tTo} °C)`);
    if (res.below) parts.push(`не выше ${res.below.hi} HRC (отпуск ${res.below.tFrom}–${res.below.tTo} °C)`);
    tb.text = `${hrc} HRC достигается только отпуском ${res.zone.tFrom}–${res.zone.tTo} °C — это хрупкость I рода, режим ЗАПРЕЩЁН (п. 3.4 ТИ). Допустимо: ${parts.join(' или ')}.`;
    tb.warning = 'Режим не назначен.';
  } else {
    tb.text = `${hrc} HRC вне режимов ТИ-40ХН (18–54 HRC после отпуска; зона 43–49 HRC запрещена). После закалки 52–56 HRC; отпуск ниже 180 °C недопустим (недоотпуск).`;
    tb.warning = 'Режим не назначен.';
  }
  blocks.push(tb);

  blocks.push({
    label: 'Обязательно',
    text:
      'Перерыв закалка → отпуск не более 1,5 ч. Шлифовка и рихтовка до отпуска запрещены.\n' +
      'Образец-свидетель ≥ 25×25 мм: после закалки 50–56 HRC; после отпуска KCU ≥ 69 Дж/см².\n' +
      'Зачистка под твердомер 0,4–0,6 мм; разброс HRC по поверхности ≤ 3–4. НК (МПД/ПВК + УЗК при Dэкв ≥ 45 мм) после отпуска и после шлифовки.',
  });

  return { gost: steel.gost, blocks, alerts };
}
