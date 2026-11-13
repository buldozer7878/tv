import React, { useMemo, useState } from 'react';
import { View, Text, TextInput, FlatList, Pressable, StyleSheet } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { colors, radius } from '../theme';
import { standards } from '../data';

export default function GostScreen({ navigation }) {
  const [query, setQuery] = useState('');

  const list = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return standards;
    return standards.filter(
      (s) =>
        s.gost.toLowerCase().includes(q) ||
        s.category.toLowerCase().includes(q) ||
        s.marks.some((m) => m.toLowerCase().includes(q))
    );
  }, [query]);

  return (
    <View style={styles.screen}>
      <Text style={styles.h1}>Справочник ГОСТ</Text>
      <View style={styles.searchBox}>
        <TextInput
          style={styles.search}
          placeholder="Поиск марки или ГОСТа…"
          placeholderTextColor={colors.muted}
          value={query}
          onChangeText={setQuery}
        />
        <Ionicons name="search" size={24} color={colors.muted} />
      </View>

      <FlatList
        data={list}
        keyExtractor={(s) => s.id}
        contentContainerStyle={{ paddingBottom: 24 }}
        ListEmptyComponent={<Text style={styles.empty}>Ничего не найдено</Text>}
        renderItem={({ item }) => (
          <Pressable
            style={({ pressed }) => [styles.card, pressed && { opacity: 0.85 }]}
            onPress={() => navigation.navigate('GostDetail', { id: item.id, title: item.gost })}
          >
            <Text style={styles.gost}>{item.gost}</Text>
            <Text style={styles.cat}>{item.category}:</Text>
            <Text style={styles.marks} numberOfLines={1}>{item.marks.slice(0, 3).join(', ')}…</Text>
          </Pressable>
        )}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.bg, paddingHorizontal: 16 },
  h1: { color: colors.text, fontSize: 26, fontWeight: '800', textAlign: 'center', marginVertical: 12 },
  searchBox: { flexDirection: 'row', alignItems: 'center', backgroundColor: colors.card, borderBottomWidth: 1.5, borderBottomColor: colors.muted, paddingHorizontal: 12, marginBottom: 14, borderTopLeftRadius: 8, borderTopRightRadius: 8 },
  search: { flex: 1, color: colors.text, fontSize: 18, paddingVertical: 14 },
  card: { backgroundColor: colors.card, borderRadius: radius.md, padding: 16, marginBottom: 12 },
  gost: { color: colors.text, fontSize: 24, fontWeight: '800' },
  cat: { color: colors.text, fontSize: 17, marginTop: 4 },
  marks: { color: colors.text, fontSize: 17 },
  empty: { color: colors.muted, textAlign: 'center', marginTop: 40, fontSize: 16 },
});
