import React, { useMemo, useState } from 'react';
import { View, Text, Pressable, Modal, TextInput, FlatList, StyleSheet, SafeAreaView } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { colors } from '../theme';
import { fieldStyles } from './Field';
import { steels, steelLabel } from '../data';

/** Выпадающий список с поиском (автокомплит) по марке и ГОСТ. */
export default function SteelPicker({ value, onChange, label = 'Марка стали' }) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const selected = steels.find((s) => s.id === value);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return steels;
    return steels.filter((s) => steelLabel(s).toLowerCase().includes(q));
  }, [query]);

  const close = () => { setOpen(false); setQuery(''); };

  return (
    <View style={fieldStyles.wrap}>
      <Text style={fieldStyles.label}>{label}</Text>
      <Pressable style={[fieldStyles.box, styles.row]} onPress={() => setOpen(true)}>
        <Text style={styles.value}>{selected ? steelLabel(selected) : 'Выберите марку'}</Text>
        <Ionicons name="caret-down" size={16} color={colors.muted} />
      </Pressable>

      <Modal visible={open} animationType="slide" onRequestClose={close}>
        <SafeAreaView style={styles.modal}>
          <View style={styles.searchRow}>
            <TextInput
              style={styles.search}
              placeholder="Поиск марки или ГОСТа…"
              placeholderTextColor={colors.muted}
              value={query}
              onChangeText={setQuery}
              autoFocus
            />
            <Pressable onPress={close} hitSlop={12}>
              <Ionicons name="close" size={30} color={colors.text} />
            </Pressable>
          </View>
          <FlatList
            data={filtered}
            keyExtractor={(s) => s.id}
            keyboardShouldPersistTaps="handled"
            ListEmptyComponent={<Text style={styles.empty}>Ничего не найдено</Text>}
            renderItem={({ item }) => (
              <Pressable
                style={[styles.item, item.id === value && { backgroundColor: colors.card }]}
                onPress={() => { onChange(item.id); close(); }}
              >
                <Text style={styles.itemName}>{item.name}</Text>
                <Text style={styles.itemGost}>{item.gost}</Text>
              </Pressable>
            )}
          />
        </SafeAreaView>
      </Modal>

      {selected ? <Text style={styles.gost}>{selected.gost}</Text> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  value: { color: colors.text, fontSize: 20, flexShrink: 1 },
  gost: { color: colors.teal, fontSize: 14, marginTop: 4, marginLeft: 4 },
  modal: { flex: 1, backgroundColor: colors.bg },
  searchRow: { flexDirection: 'row', alignItems: 'center', gap: 12, padding: 16 },
  search: { flex: 1, color: colors.text, fontSize: 18, borderBottomWidth: 1.5, borderBottomColor: colors.teal, paddingVertical: 8 },
  item: { paddingVertical: 16, paddingHorizontal: 20, borderBottomWidth: 1, borderBottomColor: colors.border },
  itemName: { color: colors.text, fontSize: 20, fontWeight: '700' },
  itemGost: { color: colors.muted, fontSize: 14, marginTop: 2 },
  empty: { color: colors.muted, textAlign: 'center', marginTop: 40, fontSize: 16 },
});
