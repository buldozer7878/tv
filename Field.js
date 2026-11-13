import React from 'react';
import { View, Text, TextInput, StyleSheet } from 'react-native';
import { colors, radius } from '../theme';

export const fieldStyles = {
  wrap: { marginBottom: 14 },
  label: { color: colors.muted, fontSize: 14, marginBottom: 4, marginLeft: 4 },
  box: {
    borderWidth: 1.5,
    borderColor: colors.teal,
    borderRadius: radius.md,
    backgroundColor: colors.bg,
    minHeight: 56,
    paddingHorizontal: 14,
    justifyContent: 'center',
  },
};

export default function Field({ label, value, onChangeText, keyboardType = 'numeric' }) {
  return (
    <View style={fieldStyles.wrap}>
      <Text style={fieldStyles.label}>{label}</Text>
      <TextInput
        style={[fieldStyles.box, styles.input]}
        value={value}
        onChangeText={onChangeText}
        keyboardType={keyboardType}
        placeholderTextColor={colors.muted}
        selectTextOnFocus
      />
    </View>
  );
}

const styles = StyleSheet.create({ input: { color: colors.text, fontSize: 20 } });
