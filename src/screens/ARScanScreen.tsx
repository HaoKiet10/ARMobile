import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
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
  ArStatusEvent,
  addAnchorFoundListener,
  addAnchorRemovedListener,
  addStatusListener,
} from '../native/ArTracking';
import { fetchPublicProject, fetchPublishedTriggers } from '../services/api';
import { PublicProject, PublishedTrigger } from '../types/ar';
import { buildOverlayConfigJson } from '../utils/arAssets';

// Chiều rộng "danh nghĩa" (mét) của ảnh trigger — ARCore dùng để ước lượng pose; cũng là hệ số quy đổi
// toạ độ editor (1 đơn vị = 100% bề rộng ảnh trigger) sang mét. Giữ khớp với editor/README.
const NOMINAL_MARKER_WIDTH = 0.15;

type ListPhase = 'loading' | 'error' | 'empty' | 'ready';

interface ActiveProject {
  id: string;
  project: PublicProject;
}

/**
 * Màn hình quét tự động: không cần nhập Project ID. App tải danh sách trigger image của mọi project đã
 * publish, ARCore nhận diện ảnh nào thì tải project đó và hiện nội dung lên.
 */
const ARScanScreen: React.FC = () => {
  const [hasCameraPermission, setHasCameraPermission] = useState(false);
  const [phase, setPhase] = useState<ListPhase>('loading');
  const [errorMsg, setErrorMsg] = useState('');
  const [triggers, setTriggers] = useState<PublishedTrigger[]>([]);
  const [reloadToken, setReloadToken] = useState(0);
  const [arStatus, setArStatus] = useState<ArStatusEvent>({ state: 'loading' });
  const [active, setActive] = useState<ActiveProject | null>(null);
  const [trackedIds, setTrackedIds] = useState<string[]>([]);
  const [contentError, setContentError] = useState<string | null>(null);

  const trackedRef = useRef<Set<string>>(new Set());
  const activeIdRef = useRef<string | null>(null);
  const retryTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    (async () => {
      if (Platform.OS === 'android') {
        const granted = await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.CAMERA, {
          title: 'Cần quyền Camera',
          message: 'App cần camera để nhận diện ảnh trigger',
          buttonPositive: 'Đồng ý',
        });
        setHasCameraPermission(granted === PermissionsAndroid.RESULTS.GRANTED);
      } else {
        setHasCameraPermission(true);
      }
    })();
  }, []);

  const loadList = useCallback(async () => {
    setPhase('loading');
    setActive(null);
    setContentError(null);
    activeIdRef.current = null;
    trackedRef.current.clear();
    setTrackedIds([]);
    setArStatus({ state: 'loading' });
    try {
      const list = await fetchPublishedTriggers();
      const valid = list.filter((t) => !!t.triggerImageUrl);
      setTriggers(valid);
      setPhase(valid.length > 0 ? 'ready' : 'empty');
    } catch (err: any) {
      setErrorMsg(err?.message ?? 'Không tải được danh sách project');
      setPhase('error');
    }
  }, []);

  useEffect(() => {
    loadList();
  }, [loadList]);

  const activate = useCallback(async (id: string) => {
    activeIdRef.current = id;
    setContentError(null);
    try {
      const project = await fetchPublicProject(id);
      if (activeIdRef.current !== id) return; // đã chuyển sang project khác trong lúc tải
      setActive({ id, project });
    } catch (err: any) {
      if (activeIdRef.current !== id) return;
      activeIdRef.current = null;
      setContentError(err?.message ?? 'Không tải được nội dung');
      // Thử lại 1 lần sau 2.5s nếu ảnh vẫn đang được nhìn thấy
      if (retryTimer.current) clearTimeout(retryTimer.current);
      retryTimer.current = setTimeout(() => {
        if (trackedRef.current.has(id) && !activeIdRef.current) activate(id);
      }, 2500);
    }
  }, []);

  useEffect(() => {
    const syncTracked = () => setTrackedIds(Array.from(trackedRef.current));

    const s1 = addAnchorFoundListener(({ name }) => {
      const cur = activeIdRef.current;
      const curTracked = cur != null && trackedRef.current.has(cur);
      trackedRef.current.add(name);
      syncTracked();
      // Đang có project active mà vẫn nhìn thấy nó -> giữ nguyên, tránh nhấp nháy khi 2 ảnh cùng vào khung
      if (!curTracked && cur !== name) activate(name);
    });
    const s2 = addAnchorRemovedListener(({ name }) => {
      trackedRef.current.delete(name);
      syncTracked();
      if (activeIdRef.current === name) {
        const other = trackedRef.current.values().next().value;
        if (other) activate(other);
        // Không có ảnh nào khác: giữ project active (nội dung đã tải) để nhìn lại là hiện ngay
      }
    });
    const s3 = addStatusListener(setArStatus);
    return () => {
      s1.remove();
      s2.remove();
      s3.remove();
      if (retryTimer.current) clearTimeout(retryTimer.current);
    };
  }, [activate]);

  const targetsJson = useMemo(
    () => JSON.stringify(triggers.map((t) => ({ name: t.id, url: t.triggerImageUrl }))),
    [triggers],
  );

  const overlayJson = useMemo(
    () => (active ? buildOverlayConfigJson(active.id, active.project.assets, NOMINAL_MARKER_WIDTH) : undefined),
    [active],
  );

  const retryAll = () => {
    setReloadToken((t) => t + 1);
    loadList();
  };

  if (!hasCameraPermission) {
    return (
      <View style={styles.center}>
        <Text style={styles.text}>Đang chờ quyền camera...</Text>
      </View>
    );
  }

  if (phase === 'loading') {
    return (
      <View style={styles.center}>
        <ActivityIndicator size="large" color="#ffffff" />
        <Text style={styles.text}>Đang tải danh sách project...</Text>
      </View>
    );
  }

  if (phase === 'error') {
    return (
      <View style={styles.center}>
        <Text style={styles.errorText}>{errorMsg}</Text>
        <TouchableOpacity style={styles.button} onPress={retryAll}>
          <Text style={styles.buttonText}>Thử lại</Text>
        </TouchableOpacity>
      </View>
    );
  }

  if (phase === 'empty') {
    return (
      <View style={styles.center}>
        <Text style={styles.errorText}>Chưa có project nào được publish.</Text>
        <TouchableOpacity style={styles.button} onPress={retryAll}>
          <Text style={styles.buttonText}>Tải lại</Text>
        </TouchableOpacity>
      </View>
    );
  }

  const activeTracked = !!active && trackedIds.includes(active.id);
  const anyTracked = trackedIds.length > 0;

  return (
    <View style={styles.flex}>
      <ArCameraView
        style={styles.flex}
        targetsJson={targetsJson}
        overlayJson={overlayJson}
        reloadToken={reloadToken}
        physicalWidth={NOMINAL_MARKER_WIDTH}
      />

      {arStatus.state === 'ready' && !anyTracked && (
        <View style={styles.overlay} pointerEvents="none">
          <View style={styles.scanFrame} />
          <Text style={styles.overlayText}>Đưa camera vào ảnh trigger để bắt đầu</Text>
        </View>
      )}

      {arStatus.state === 'ready' && anyTracked && (
        <View style={styles.badge} pointerEvents="none">
          <Text style={styles.badgeText}>
            {activeTracked ? `Đã nhận diện: ${active!.project.name}` : 'Đang tải nội dung...'}
          </Text>
        </View>
      )}

      {contentError && anyTracked && (
        <View style={[styles.badge, styles.badgeError]} pointerEvents="none">
          <Text style={styles.badgeText}>{contentError}</Text>
        </View>
      )}

      {arStatus.state === 'loading' && (
        <View style={styles.cover}>
          <ActivityIndicator size="large" color="#ffffff" />
          <Text style={styles.text}>{arStatus.message ?? 'Đang chuẩn bị nhận diện...'}</Text>
        </View>
      )}

      {arStatus.state === 'error' && (
        <View style={styles.cover}>
          <Text style={styles.errorText}>{arStatus.message ?? 'Không khởi tạo được AR'}</Text>
          <TouchableOpacity style={styles.button} onPress={retryAll}>
            <Text style={styles.buttonText}>Thử lại</Text>
          </TouchableOpacity>
        </View>
      )}
    </View>
  );
};

const styles = StyleSheet.create({
  flex: { flex: 1 },
  center: { flex: 1, backgroundColor: '#000', alignItems: 'center', justifyContent: 'center', padding: 24 },
  cover: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    backgroundColor: 'rgba(0,0,0,0.75)',
    alignItems: 'center',
    justifyContent: 'center',
    padding: 24,
  },
  text: { color: '#fff', marginTop: 12, fontSize: 16, textAlign: 'center' },
  errorText: { color: '#fff', fontSize: 16, textAlign: 'center', marginBottom: 16 },
  button: { backgroundColor: '#2f6fed', paddingHorizontal: 24, paddingVertical: 12, borderRadius: 8 },
  buttonText: { color: '#fff', fontWeight: '600' },
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
  badge: {
    position: 'absolute',
    top: 60,
    alignSelf: 'center',
    backgroundColor: 'rgba(40,180,99,0.9)',
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 20,
  },
  badgeError: { top: 110, backgroundColor: 'rgba(200,60,60,0.9)' },
  badgeText: { color: '#fff', fontWeight: '600' },
});

export default ARScanScreen;