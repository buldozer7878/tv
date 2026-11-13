import React from 'react';
import { Text, StyleSheet } from 'react-native';
import { colors } from '../theme';
import { isTi, sourceLabel } from '../data';

export default function SourceBadge({ steel }) {
  const ti = isTi(steel);
  return <Text style={[styles.t, { color: ti ? colors.teal : colors.orange }]}>{ti ? '✔ ' : '⚠ '}{sourceLabel(steel)}</Text>;
}

const styles = StyleSheet.create({ t: { fontSize: 14, fontWeight: '600', marginBottom: 10 } });
