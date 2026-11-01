import React from 'react';
import { StyleSheet, StatusBar } from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import ARScanScreen from './src/screens/ARScanScreen';

// Không có màn nhập Project ID: app tự nhận diện project qua ảnh trigger.
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