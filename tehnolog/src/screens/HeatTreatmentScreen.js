import React, { useState } from 'react';
import { ScrollView, Text, StyleSheet } from 'react-native';
import { colors } from '../theme';
import { DEFAULT_STEEL_ID, getSteelById } from '../data';
import { SHAPES, calcHeatTreatment } from '../logic/heatTreatment';
import SteelPicker from '../components/SteelPicker';
import Segmented from '../components/Segmented';
import Field from '../components/Field';
import PrimaryButton from '../components/PrimaryButton';
import { ResultCard, ResultBlock } from '../components/ResultCard';

const num = (s) => parseFloat(String(s).replace(',', '.'));

export default function HeatTreatmentScreen() {
  const [steelId, setSteelId] = useState(DEFAULT_STEEL_ID);
  const [shape, setShape] = useState('Вал');
  const [size, setSize] = useState('50');
  const [hrc, setHrc] = useState('38');
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);

  const onCalc = () => {
    const sizeN = num(size);
    const hrcN = num(hrc);
    if (!(sizeN > 0)) return setError('Введите размер больше 0 мм');
    if (!(hrcN > 0)) return setError('Введите требуемую твёрдость HRC');
    setError(null);
    setResult(calcHeatTreatment(getSteelById(steelId), { shape, size: sizeN, hrc: hrcN }));
  };

  return (
    <ScrollView style={styles.screen} contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
      <Text style={styles.h1}>Термообработка</Text>

      <SteelPicker value={steelId} onChange={(id) => { setSteelId(id); setResult(null); }} />
      <Segmented label="Форма детали" options={SHAPES} value={shape} onChange={setShape} />
      <Field label="Размер (Ø / толщина), мм" value={size} onChangeText={setSize} />
      <Field label="Требуемая твердость, HRC" value={hrc} onChangeText={setHrc} />

      {error ? <Text style={styles.error}>{error}</Text> : null}
      <PrimaryButton title="РАССЧИТАТЬ" onPress={onCalc} />

      {result && (
        <ResultCard title={`РЕЗУЛЬТАТЫ РАСЧЕТА (${result.gost})`}>
          {result.quench && (
            <ResultBlock label="Закалка">
              Нагрев {result.quench.tMin}–{result.quench.tMax} °C, среда {result.quench.medium}, выдержка ~ {result.quench.holdMin} мин.
            </ResultBlock>
          )}
          {result.temper && (
            <ResultBlock label="Отпуск" warning={result.temper.warning}>
              Нагрев {result.temper.temp} °C, охлаждение {result.temper.cooling}.
            </ResultBlock>
          )}
          {result.note && <ResultBlock label="Примечание">{result.note}</ResultBlock>}
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
