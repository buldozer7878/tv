import React from 'react';
import { View, Text, Pressable, StyleSheet } from 'react-native';
import { colors, radius } from '../theme';
import { fieldStyles } from './Field';

/** Крупный переключатель вместо Picker — удобнее в перчатках. */
export default function Segmented({ label, options, value, onChange }) {
  return (
    <View style={fieldStyles.wrap}>
      <Text style={fieldStyles.label}>{label}</Text>
      <View style={styles.row}>
        {options.map((o) => {
          const active = o === value;
          return (
            <Pressable key={o} onPress={() => onChange(o)} style={[styles.item, active && styles.active]}>
              <Text style={[styles.text, active && styles.textActive]}>{o}</Text>
            </Pressable>
          );
        })}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  item: { flexGrow: 1, minHeight: 52, minWidth: '22%', borderRadius: radius.md, borderWidth: 1.5, borderColor: colors.border, backgroundColor: colors.bg, alignItems: 'center', justifyContent: 'center' },
  active: { borderColor: colors.teal, backgroundColor: '#0B2E36' },
  text: { color: colors.muted, fontSize: 17, fontWeight: '600' },
  textActive: { color: colors.teal },
});
