import React from 'react';
import { StyleSheet, StatusBar } from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import ARScanScreen from './src/screens/ARScanScreen';

// Không còn màn nhập Project ID: app tự nhận diện project qua ảnh trigger.
// (ProjectIdEntryScreen.tsx không còn được dùng — có thể xoá hoặc giữ lại làm công cụ debug.)
const App: React.FC = () => {
  return (
    <SafeAreaProvider>
      <SafeAreaView style={styles.flex}>
        <StatusBar barStyle="light-content" backgroundColor="#000" />
        <ARScanScreen />
      </SafeAreaView>
    </SafeAreaProvider>
  );
};

const styles = StyleSheet.create({
  flex: { flex: 1, backgroundColor: '#000' },
});

export default App;