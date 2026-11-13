import React from 'react';
import { Pressable, Text, StyleSheet } from 'react-native';
import { colors, radius } from '../theme';

export default function PrimaryButton({ title, onPress }) {
  return (
    <Pressable onPress={onPress} style={({ pressed }) => [styles.btn, pressed && { opacity: 0.8 }]}>
      <Text style={styles.text}>{title}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  btn: { backgroundColor: colors.teal, borderRadius: radius.pill, minHeight: 60, alignItems: 'center', justifyContent: 'center', marginVertical: 12 },
  text: { color: '#00262E', fontSize: 20, fontWeight: '800', letterSpacing: 0.5 },
});
