import React, { useEffect, useState } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ActivityIndicator,
  TouchableOpacity,
  PermissionsAndroid,
  Platform,
} from 'react-native';
import {
  ArCameraView,
  addAnchorFoundListener,
  addAnchorRemovedListener,
} from '../native/ArTracking';
import { fetchPublicProject } from '../services/api';
import { PublicProject } from '../types/ar';

// Khớp NOMINAL_MARKER_WIDTH cũ trong ARSceneContent.tsx (react-viro) — chiều rộng
// "danh nghĩa" ARCore dùng để ước lượng pose, KHÔNG phải kích thước thật tuyệt đối
// của từng trigger image. Giữ nguyên 0.15 để không đổi hành vi tracking đã test ổn ở VBTest.
const NOMINAL_MARKER_WIDTH = 0.15;

type LoadState = 'loading' | 'ready' | 'error';

interface Props {
  projectId: string;
  onBack?: () => void;
}

const ARScanScreen: React.FC<Props> = ({ projectId, onBack }) => {
  const [project, setProject] = useState<PublicProject | null>(null);
  const [loadState, setLoadState] = useState<LoadState>('loading');
  const [errorMsg, setErrorMsg] = useState<string>('');
  const [isFound, setIsFound] = useState(false);
  const [hasCameraPermission, setHasCameraPermission] = useState(false);

  const loadProject = async () => {
    setLoadState('loading');
    try {
      const data = await fetchPublicProject(projectId);
      if (!data.triggerImageUrl) {
        throw new Error('Project chưa có trigger image');
      }
      setProject(data);
      setLoadState('ready');
    } catch (err: any) {
      setErrorMsg(err?.message ?? 'Không tải được project');
      setLoadState('error');
    }
  };

  useEffect(() => {
    loadProject();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId]);

  // react-viro trước đây tự xin quyền camera nội bộ. Native module tự viết
  // KHÔNG làm việc này -> phải xin thủ công trước khi mount ArCameraView.
  useEffect(() => {
    (async () => {
      if (Platform.OS === 'android') {
        const granted = await PermissionsAndroid.request(
          PermissionsAndroid.PERMISSIONS.CAMERA,
          {
            title: 'Cần quyền Camera',
            message: 'App cần camera để nhận diện trigger image',
            buttonPositive: 'Đồng ý',
          },
        );
        setHasCameraPermission(granted === PermissionsAndroid.RESULTS.GRANTED);
      } else {
        setHasCameraPermission(true);
      }
    })();
  }, []);

  useEffect(() => {
    const s1 = addAnchorFoundListener(() => setIsFound(true));
    const s2 = addAnchorRemovedListener(() => setIsFound(false));
    return () => {
      s1.remove();
      s2.remove();
    };
  }, []);

  if (loadState === 'loading') {
    return (
      <View style={styles.centerContainer}>
        <ActivityIndicator size="large" color="#ffffff" />
        <Text style={styles.loadingText}>Đang tải project...</Text>
      </View>
    );
  }

  if (loadState === 'error' || !project) {
    return (
      <View style={styles.centerContainer}>
        <Text style={styles.errorText}>{errorMsg}</Text>
        <TouchableOpacity style={styles.retryButton} onPress={loadProject}>
          <Text style={styles.retryButtonText}>Thử lại</Text>
        </TouchableOpacity>
        {onBack && (
          <TouchableOpacity style={styles.backButton} onPress={onBack}>
            <Text style={styles.backButtonText}>Quay lại</Text>
          </TouchableOpacity>
        )}
      </View>
    );
  }

  if (!hasCameraPermission) {
    return (
      <View style={styles.centerContainer}>
        <Text style={styles.loadingText}>Đang chờ quyền camera...</Text>
      </View>
    );
  }

  return (
    <View style={styles.flex}>
      {/* TODO (bước polish sau khi validate flow xong): thay overlay cube placeholder
          trong native module bằng render layer Filament thật, dùng project.assets +
          transform (position/rotation/scale) — logic quy đổi relativeToAR() cũ trong
          ARSceneContent.tsx vẫn còn giá trị tham khảo, chưa xoá. */}
      <ArCameraView
        style={styles.flex}
        targetImageUrl={project.triggerImageUrl!}
        targetName="active_project_trigger"
        physicalWidth={NOMINAL_MARKER_WIDTH}
        overlayBoxSize={0.05}
      />

      {!isFound && (
        <View style={styles.overlay} pointerEvents="none">
          <View style={styles.scanFrame} />
          <Text style={styles.overlayText}>
            Đưa camera vào ảnh trigger của "{project.name}"
          </Text>
        </View>
      )}

      {isFound && (
        <View style={styles.foundBadge} pointerEvents="none">
          <Text style={styles.foundBadgeText}>Đã nhận diện: {project.name}</Text>
        </View>
      )}
    </View>
  );
};

const styles = StyleSheet.create({
  flex: { flex: 1 },
  centerContainer: {
    flex: 1,
    backgroundColor: '#000',
    alignItems: 'center',
    justifyContent: 'center',
    padding: 24,
  },
  loadingText: { color: '#fff', marginTop: 12, fontSize: 16 },
  errorText: {
    color: '#fff',
    fontSize: 16,
    textAlign: 'center',
    marginBottom: 16,
  },
  retryButton: {
    backgroundColor: '#2f6fed',
    paddingHorizontal: 24,
    paddingVertical: 12,
    borderRadius: 8,
    marginBottom: 12,
  },
  retryButtonText: { color: '#fff', fontWeight: '600' },
  backButton: { paddingVertical: 8 },
  backButtonText: { color: '#aaa' },
  overlay: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    alignItems: 'center',
    justifyContent: 'center',
  },
  scanFrame: {
    width: 240,
    height: 240,
    borderWidth: 2,
    borderColor: 'rgba(255,255,255,0.8)',
    borderRadius: 16,
  },
  overlayText: {
    marginTop: 16,
    color: '#fff',
    fontSize: 15,
    textAlign: 'center',
    backgroundColor: 'rgba(0,0,0,0.5)',
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 6,
    maxWidth: 280,
  },
  foundBadge: {
    position: 'absolute',
    top: 60,
    alignSelf: 'center',
    backgroundColor: 'rgba(40,180,99,0.9)',
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 20,
  },
  foundBadgeText: { color: '#fff', fontWeight: '600' },
});

export default ARScanScreen;
