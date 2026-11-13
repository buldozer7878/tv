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

/** Оранжевый заголовок + значение */
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

const styles = StyleSheet.create({
  card: { backgroundColor: colors.card, borderRadius: radius.md, padding: 16, marginTop: 4 },
  title: { color: colors.text, fontSize: 18, fontWeight: '800', marginBottom: 12 },
  block: { marginBottom: 14 },
  label: { color: colors.orange, fontWeight: '800' },
  value: { color: colors.text, fontSize: 18, lineHeight: 26 },
  warning: { color: colors.danger, fontSize: 14, marginTop: 4 },
});
