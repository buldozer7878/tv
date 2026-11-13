import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { colors, radius } from '../theme';

export function ResultCard({ title, children }) {
  return (
    <View style={styles.card}>
      <Text style={styles.title}>{title}</Text>
      {children}
    </View>
  );
}

/** Оранжевый заголовок + значение (+ красное предупреждение) */
export function ResultBlock({ label, children, warning }) {
  return (
    <View style={styles.block}>
      <Text style={styles.value}>
        <Text style={styles.label}>{label}: </Text>
        {children}
      </Text>
      {warning ? <Text style={styles.warning}>⚠ {warning}</Text> : null}
    </View>
  );
}

/** Блоки из логики: [{label, text, warning}] */
export function Blocks({ blocks }) {
  return blocks.map((b, i) => (
    <ResultBlock key={i} label={b.label} warning={b.warning}>
      {'\n'}{b.text}
    </ResultBlock>
  ));
}

export function Alerts({ alerts }) {
  if (!alerts || !alerts.length) return null;
  return (
    <View style={styles.alerts}>
      {alerts.map((a, i) => (
        <Text key={i} style={styles.alertText}>• {a}</Text>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  card: { backgroundColor: colors.card, borderRadius: radius.md, padding: 16, marginTop: 4 },
  title: { color: colors.text, fontSize: 18, fontWeight: '800', marginBottom: 10 },
  block: { marginBottom: 14 },
  label: { color: colors.orange, fontWeight: '800' },
  value: { color: colors.text, fontSize: 17, lineHeight: 25 },
  warning: { color: colors.danger, fontSize: 14, lineHeight: 20, marginTop: 4 },
  alerts: { borderLeftWidth: 3, borderLeftColor: colors.orange, paddingLeft: 10, marginBottom: 14 },
  alertText: { color: colors.muted, fontSize: 14, lineHeight: 20, marginBottom: 4 },
});
