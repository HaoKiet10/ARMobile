import React, { useState } from 'react';
import {
  View,
  Text,
  TextInput,
  TouchableOpacity,
  StyleSheet,
  KeyboardAvoidingView,
  Platform,
} from 'react-native';

interface Props {
  onSubmit: (projectId: string) => void;
}

/**
 * MVP: nhập tay Project ID để test flow scan trước khi có QR code/deep link thật.
 * Sau này thay bằng: quét QR code chứa URL dạng myarapp://scan/<projectId>,
 * hoặc universal link mở thẳng vào ARScanScreen.
 */
const ProjectIdEntryScreen: React.FC<Props> = ({ onSubmit }) => {
  const [value, setValue] = useState('');

  return (
    <KeyboardAvoidingView
      style={styles.flex}
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
    >
      <View style={styles.container}>
        <Text style={styles.title}>Nhập Project ID</Text>
        <Text style={styles.subtitle}>
          (Tạm thời cho MVP — bản chính thức sẽ quét QR code)
        </Text>
        <TextInput
          style={styles.input}
          value={value}
          onChangeText={setValue}
          placeholder="vd: 3f2a1b9c-..."
          placeholderTextColor="#888"
          autoCapitalize="none"
          autoCorrect={false}
        />
        <TouchableOpacity
          style={[styles.button, !value.trim() && styles.buttonDisabled]}
          disabled={!value.trim()}
          onPress={() => onSubmit(value.trim())}
        >
          <Text style={styles.buttonText}>Bắt đầu quét</Text>
        </TouchableOpacity>
      </View>
    </KeyboardAvoidingView>
  );
};

const styles = StyleSheet.create({
  flex: { flex: 1 },
  container: {
    flex: 1,
    backgroundColor: '#000',
    justifyContent: 'center',
    padding: 24,
  },
  title: { color: '#fff', fontSize: 22, fontWeight: '700', marginBottom: 6 },
  subtitle: { color: '#888', fontSize: 13, marginBottom: 20 },
  input: {
    backgroundColor: '#1a1a1a',
    color: '#fff',
    borderRadius: 8,
    paddingHorizontal: 14,
    paddingVertical: 12,
    fontSize: 15,
    marginBottom: 16,
    borderWidth: 1,
    borderColor: '#333',
  },
  button: {
    backgroundColor: '#2f6fed',
    borderRadius: 8,
    paddingVertical: 14,
    alignItems: 'center',
  },
  buttonDisabled: { backgroundColor: '#444' },
  buttonText: { color: '#fff', fontWeight: '600', fontSize: 16 },
});

export default ProjectIdEntryScreen;
