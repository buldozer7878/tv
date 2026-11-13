import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { colors } from '../theme';

export default function AppHeaderTitle() {
  return (
    <View style={styles.row}>
      <Ionicons name="settings" size={26} color={colors.text} />
      <Text style={styles.text}>ТЕХНОЛОГ</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  text: { color: colors.text, fontSize: 22, fontWeight: '800', letterSpacing: 0.5 },
});
