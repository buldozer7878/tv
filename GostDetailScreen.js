import React from 'react';
import { ScrollView, Text, View, StyleSheet } from 'react-native';
import { colors, radius } from '../theme';
import { standards, steels, temperRange } from '../data';
import SourceBadge from '../components/SourceBadge';
import { ResultBlock } from '../components/ResultCard';

export default function GostDetailScreen({ route }) {
  const std = standards.find((s) => s.id === route.params.id);
  const inDb = steels.filter((s) => s.gost === std.gost);

  return (
    <ScrollView style={styles.screen} contentContainerStyle={{ padding: 16, paddingBottom: 32 }}>
      <Text style={styles.gost}>{std.gost}</Text>
      <Text style={styles.cat}>{std.category}</Text>
      <Text style={styles.desc}>{std.description}</Text>

      <Text style={styles.h2}>Марки</Text>
      <View style={styles.chips}>
        {std.marks.map((m) => (
          <Text key={m} style={styles.chip}>{m}</Text>
        ))}
      </View>

      {inDb.length > 0 && <Text style={styles.h2}>Режимы в базе приложения</Text>}
      {inDb.map((s) => (
        <View key={s.id} style={styles.card}>
          <Text style={styles.name}>{s.name}</Text>
          <SourceBadge steel={s} />
          {s.quench ? (
            <ResultBlock label="Закалка">{s.quench.tMin}–{s.quench.tMax} °C, {s.quench.medium}</ResultBlock>
          ) : null}
          {temperRange(s) ? (
            <ResultBlock label="Отпуск">{temperRange(s)[0]}–{temperRange(s)[1]} °C (по твёрдости)</ResultBlock>
          ) : null}
          {s.heatNote ? <ResultBlock label="Т.О.">{s.heatNote}</ResultBlock> : null}
          <ResultBlock label="Электрод">{s.weld.electrode}</ResultBlock>
          <ResultBlock label="Проволока">{s.weld.wire}</ResultBlock>
          <ResultBlock label="Подогрев">{s.weld.preheat}</ResultBlock>
        </View>
      ))}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.bg },
  gost: { color: colors.text, fontSize: 28, fontWeight: '800' },
  cat: { color: colors.teal, fontSize: 18, marginTop: 4 },
  desc: { color: colors.muted, fontSize: 16, marginTop: 10, lineHeight: 22 },
  h2: { color: colors.orange, fontSize: 18, fontWeight: '800', marginTop: 22, marginBottom: 10 },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  chip: { color: colors.text, backgroundColor: colors.card, borderRadius: 20, paddingHorizontal: 14, paddingVertical: 8, fontSize: 16, overflow: 'hidden' },
  card: { backgroundColor: colors.card, borderRadius: radius.md, padding: 16, marginBottom: 12 },
  name: { color: colors.text, fontSize: 20, fontWeight: '800', marginBottom: 10 },
});
