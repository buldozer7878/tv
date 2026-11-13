import React, { useState } from 'react';
import { ScrollView, Text, StyleSheet } from 'react-native';
import { colors } from '../theme';
import { DEFAULT_STEEL_ID, getSteelById } from '../data';
import { calcWelding } from '../logic/welding';
import SteelPicker from '../components/SteelPicker';
import Field from '../components/Field';
import PrimaryButton from '../components/PrimaryButton';
import { ResultCard, ResultBlock } from '../components/ResultCard';

export default function WeldingScreen() {
  const [steelId, setSteelId] = useState(DEFAULT_STEEL_ID);
  const [thickness, setThickness] = useState('20');
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);

  const onCalc = () => {
    const t = parseFloat(String(thickness).replace(',', '.'));
    if (!(t > 0)) return setError('Введите толщину больше 0 мм');
    setError(null);
    setResult(calcWelding(getSteelById(steelId), t));
  };

  return (
    <ScrollView style={styles.screen} contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
      <Text style={styles.h1}>Сварка и ТО</Text>

      <SteelPicker value={steelId} onChange={(id) => { setSteelId(id); setResult(null); }} />
      <Field label="Толщина детали, мм" value={thickness} onChangeText={setThickness} />

      {error ? <Text style={styles.error}>{error}</Text> : null}
      <PrimaryButton title="РАССЧИТАТЬ" onPress={onCalc} />

      {result && (
        <ResultCard title="РЕЗУЛЬТАТЫ РАСЧЕТА">
          <ResultBlock label="Материалы">
            {'\n'}Электрод: {result.electrode}{'\n'}Проволока: {result.wire}
          </ResultBlock>
          <ResultBlock label="Подогрев">{'\n'}{result.preheat}</ResultBlock>
          <ResultBlock label="ТО после сварки (отпуск)">
            {'\n'}
            {result.pwht
              ? `Нагрев ${result.pwht.tMin}–${result.pwht.tMax} °C, выдержка ~${result.pwht.holdHours} ч. Охлаждение ${result.pwht.cooling}.${result.pwht.required ? '' : ' (по необходимости)'}`
              : 'Не требуется.'}
          </ResultBlock>
          {result.note ? <ResultBlock label="Примечание">{result.note}</ResultBlock> : null}
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
