import React from 'react';
import { StatusBar } from 'expo-status-bar';
import { NavigationContainer, DarkTheme } from '@react-navigation/native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { Ionicons } from '@expo/vector-icons';

import { colors } from './src/theme';
import AppHeaderTitle from './src/components/AppHeaderTitle';
import HeatTreatmentScreen from './src/screens/HeatTreatmentScreen';
import WeldingScreen from './src/screens/WeldingScreen';
import GostScreen from './src/screens/GostScreen';
import GostDetailScreen from './src/screens/GostDetailScreen';

const Tab = createBottomTabNavigator();
const Stack = createNativeStackNavigator();

const navTheme = {
  ...DarkTheme,
  colors: { ...DarkTheme.colors, background: colors.bg, card: colors.header, text: colors.text, border: colors.border, primary: colors.teal },
};

const headerOptions = {
  headerStyle: { backgroundColor: colors.header },
  headerTintColor: colors.text,
  headerTitle: () => <AppHeaderTitle />,
  headerShadowVisible: false,
};

function ReferenceStack() {
  return (
    <Stack.Navigator screenOptions={headerOptions}>
      <Stack.Screen name="GostList" component={GostScreen} />
      <Stack.Screen
        name="GostDetail"
        component={GostDetailScreen}
        options={({ route }) => ({ headerTitle: route.params.title })}
      />
    </Stack.Navigator>
  );
}

const icons = { TO: 'settings', Welding: 'flame', Reference: 'book' };

export default function App() {
  return (
    <SafeAreaProvider>
      <StatusBar style="light" />
      <NavigationContainer theme={navTheme}>
        <Tab.Navigator
          screenOptions={({ route }) => ({
            ...headerOptions,
            tabBarActiveTintColor: colors.teal,
            tabBarInactiveTintColor: colors.muted,
            tabBarStyle: { backgroundColor: colors.header, borderTopColor: colors.border, height: 64, paddingBottom: 8, paddingTop: 6 },
            tabBarLabelStyle: { fontSize: 13, fontWeight: '600' },
            tabBarIcon: ({ color }) => <Ionicons name={icons[route.name]} size={26} color={color} />,
          })}
        >
          <Tab.Screen name="TO" component={HeatTreatmentScreen} options={{ tabBarLabel: 'Т.О.' }} />
          <Tab.Screen name="Welding" component={WeldingScreen} options={{ tabBarLabel: 'Сварка' }} />
          <Tab.Screen name="Reference" component={ReferenceStack} options={{ tabBarLabel: 'Справочник', headerShown: false }} />
        </Tab.Navigator>
      </NavigationContainer>
    </SafeAreaProvider>
  );
}
