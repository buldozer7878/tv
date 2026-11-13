import React, { useState } from 'react';
import { ScrollView, Text, StyleSheet } from 'react-native';
import { colors } from '../theme';
import { DEFAULT_STEEL_ID, getSteelById } from '../data';
import { SHAPES, calcHeatTreatment } from '../logic/heatTreatment';
import { isHollow, sizeLabels } from '../logic/shape';
import { STACKING } from '../logic/models/ti40khn';
import SteelPicker from '../components/SteelPicker';
import Segmented from '../components/Segmented';
import Field from '../components/Field';
import PrimaryButton from '../components/PrimaryButton';
import SourceBadge from '../components/SourceBadge';
import { ResultCard, Blocks, Alerts } from '../components/ResultCard';

const num = (s) => parseFloat(String(s).replace(',', '.'));

export default function HeatTreatmentScreen() {
  const [steelId, setSteelId] = useState(DEFAULT_STEEL_ID);
  const [shape, setShape] = useState('Вал');
  const [size, setSize] = useState('50');
  const [inner, setInner] = useState('20');
  const [hole, setHole] = useState(null);
  const [stacking, setStacking] = useState('single');
  const [hardness, setHardness] = useState('38');
  const [unit, setUnit] = useState('HRC');
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);

  const steel = getSteelById(steelId);
  const labels = sizeLabels(shape);
  const holeOptions = steel.holeOptions || [];
  const holeId = hole && holeOptions.some((o) => o.id === hole) ? hole : holeOptions[0]?.id;
  const units = steel.hardnessUnits || ['HRC'];
  const unitSafe = units.includes(unit) ? unit : 'HRC';

  const onCalc = () => {
    const r = calcHeatTreatment(steel, {
      shape, size: num(size), inner: num(inner), hole: holeId, stacking, hrc: num(hardness), unit: unitSafe,
    });
    if (r.error) { setError(r.error); setResult(null); return; }
    setError(null);
    setResult(r);
  };

  return (
    <ScrollView style={styles.screen} contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
      <Text style={styles.h1}>Термообработка</Text>

      <SteelPicker value={steelId} onChange={(id) => { setSteelId(id); setResult(null); }} />
      <SourceBadge steel={steel} />
      <Segmented label="Форма детали" options={SHAPES} value={shape} onChange={setShape} />
      <Field label={labels.size} value={size} onChangeText={setSize} />
      {isHollow(shape) && <Field label={labels.inner} value={inner} onChangeText={setInner} />}
      {isHollow(shape) && holeOptions.length > 0 && (
        <Segmented
          label={steel.holeLabel}
          options={holeOptions.map((o) => o.label)}
          value={holeOptions.find((o) => o.id === holeId)?.label}
          onChange={(l) => setHole(holeOptions.find((o) => o.label === l).id)}
        />
      )}
      {steel.model === 'ti40khn' && (
        <Segmented
          label="Укладка в печи"
          options={STACKING.map((x) => x.label)}
          value={STACKING.find((x) => x.id === stacking).label}
          onChange={(l) => setStacking(STACKING.find((x) => x.label === l).id)}
        />
      )}
      {units.length > 1 && <Segmented label="Шкала твёрдости" options={units} value={unitSafe} onChange={setUnit} />}
      <Field label={`Требуемая твердость, ${unitSafe}`} value={hardness} onChangeText={setHardness} />

      {error ? <Text style={styles.error}>{error}</Text> : null}
      <PrimaryButton title="РАССЧИТАТЬ" onPress={onCalc} />

      {result && (
        <ResultCard title={`РЕЗУЛЬТАТЫ РАСЧЕТА (${result.gost})`}>
          <SourceBadge steel={steel} />
          <Alerts alerts={result.alerts} />
          <Blocks blocks={result.blocks} />
        </ResultCard>
      )}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.bg },
  content: { padding: 16, paddingBottom: 32 },
  h1: { color: colors.text, fontSize: 26, fontWeight: '800', textAlign: 'center', marginVertical: 12 },
  error: { color: colors.danger, fontSize: 15, marginLeft: 4 },
});
