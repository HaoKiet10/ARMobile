import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ActivityIndicator,
  TouchableOpacity,
  PermissionsAndroid,
  Platform,
  AppState,
  Linking,
  Vibration,
} from 'react-native';
import ScanFrame from '../components/ScanFrame';
import {
  ArCameraView,
  ArStatusEvent,
  addAnchorFoundListener,
  addAnchorRemovedListener,
  addStatusListener,
} from '../native/ArTracking';
import { API_BASE_URL } from '../config';
import { ApiError, fetchPublicProject, fetchTriggerDb } from '../services/api';
import { PublicProject, TriggerDb } from '../types/ar';
import { buildOverlayConfigJson } from '../utils/arAssets';

// Chiều rộng "danh nghĩa" (mét) của ảnh trigger — hệ số quy đổi toạ độ editor (1 đơn vị = 100% bề rộng ảnh
// trigger) sang mét. PHẢI khớp NOMINAL_MARKER_WIDTH_M của backend (trigger-db.service.ts): server ghi đúng
// chiều rộng này vào database nhận diện để ARCore ước lượng pose. Giữ khớp với editor/README.
const NOMINAL_MARKER_WIDTH = 0.15;

// Lỗi tạm thời (server đang thức dậy / đang dựng database, rớt mạng) thì tự thử lại vài lần trước khi báo lỗi
const MAX_AUTO_RETRY = 4;
const AUTO_RETRY_DELAY_MS = 4000;
// Sau ngần này mà vẫn đang tải thì giải thích cho người dùng là server có thể đang khởi động
const SLOW_HINT_DELAY_MS = 6000;

type ListPhase = 'loading' | 'error' | 'empty' | 'ready';
type CameraPermission = 'checking' | 'granted' | 'denied' | 'blocked';

const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

interface ActiveProject {
  id: string;
  project: PublicProject;
}

/**
 * Màn hình quét tự động: không cần nhập Project ID. App tải danh sách trigger image của mọi project đã
 * publish, ARCore nhận diện ảnh nào thì tải project đó và hiện nội dung lên.
 */
const ARScanScreen: React.FC = () => {
  const [permission, setPermission] = useState<CameraPermission>('checking');
  const [phase, setPhase] = useState<ListPhase>('loading');
  const [loadingMsg, setLoadingMsg] = useState('');
  const [slowHint, setSlowHint] = useState(false);
  const [errorMsg, setErrorMsg] = useState('');
  const [triggerDb, setTriggerDb] = useState<TriggerDb | null>(null);
  const [reloadToken, setReloadToken] = useState(0);
  const [arStatus, setArStatus] = useState<ArStatusEvent>({ state: 'loading' });
  const [active, setActive] = useState<ActiveProject | null>(null);
  const [trackedIds, setTrackedIds] = useState<string[]>([]);
  const [contentError, setContentError] = useState<string | null>(null);

  const trackedRef = useRef<Set<string>>(new Set());
  const activeIdRef = useRef<string | null>(null);
  const retryTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  // Mỗi lần loadList tăng số này; vòng tải cũ thấy lệch số thì dừng (bấm "Thử lại" giữa chừng, unmount)
  const loadSeq = useRef(0);

  const requestCamera = useCallback(async () => {
    if (Platform.OS !== 'android') {
      setPermission('granted');
      return;
    }
    const result = await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.CAMERA, {
      title: 'Cho phép dùng camera',
      message: 'App cần camera để nhận diện ảnh và hiển thị nội dung AR.',
      buttonPositive: 'Đồng ý',
    });
    if (result === PermissionsAndroid.RESULTS.GRANTED) setPermission('granted');
    else if (result === PermissionsAndroid.RESULTS.NEVER_ASK_AGAIN) setPermission('blocked');
    else setPermission('denied');
  }, []);

  useEffect(() => {
    requestCamera();
  }, [requestCamera]);

  // Người dùng bật quyền trong Cài đặt rồi quay lại app -> vào màn quét luôn, không bắt bấm gì thêm
  useEffect(() => {
    if (permission !== 'denied' && permission !== 'blocked') return;
    const sub = AppState.addEventListener('change', async (state) => {
      if (state === 'active' && (await PermissionsAndroid.check(PermissionsAndroid.PERMISSIONS.CAMERA))) {
        setPermission('granted');
      }
    });
    return () => sub.remove();
  }, [permission]);

  const loadList = useCallback(async () => {
    const seq = ++loadSeq.current;
    setPhase('loading');
    setLoadingMsg('Đang kết nối máy chủ...');
    setSlowHint(false);
    setActive(null);
    setContentError(null);
    activeIdRef.current = null;
    trackedRef.current.clear();
    setTrackedIds([]);
    setArStatus({ state: 'loading' });
    const slowTimer = setTimeout(() => {
      if (loadSeq.current === seq) setSlowHint(true);
    }, SLOW_HINT_DELAY_MS);
    try {
      for (let attempt = 0; ; attempt++) {
        try {
          const db = await fetchTriggerDb();
          if (loadSeq.current !== seq) return;
          setTriggerDb(db);
          setPhase(db.url && db.triggers.length > 0 ? 'ready' : 'empty');
          return;
        } catch (err: any) {
          if (loadSeq.current !== seq) return;
          if (err instanceof ApiError && err.retryable && attempt < MAX_AUTO_RETRY) {
            setLoadingMsg(err.message);
            await sleep(AUTO_RETRY_DELAY_MS);
            if (loadSeq.current !== seq) return;
            continue;
          }
          setErrorMsg(err?.message ?? 'Không tải được dữ liệu. Vui lòng thử lại.');
          setPhase('error');
          return;
        }
      }
    } finally {
      clearTimeout(slowTimer);
    }
  }, []);

  const cancelLoad = useCallback(() => {
    loadSeq.current++; // vòng tải đang chạy thấy lệch số -> dừng
  }, []);

  useEffect(() => {
    loadList();
    return cancelLoad;
  }, [loadList, cancelLoad]);

  const activate = useCallback(async (id: string) => {
    activeIdRef.current = id;
    setContentError(null);
    try {
      const project = await fetchPublicProject(id);
      if (activeIdRef.current !== id) return; // đã chuyển sang project khác trong lúc tải
      setActive({ id, project });
      Vibration.vibrate(40); // rung nhẹ báo đã nhận diện
    } catch (err: any) {
      if (activeIdRef.current !== id) return;
      activeIdRef.current = null;
      setContentError(err?.message ?? 'Không tải được nội dung. Đang thử lại...');
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

  const triggerDbJson = useMemo(
    () =>
      triggerDb?.url
        ? JSON.stringify({ version: triggerDb.version, url: triggerDb.url, sha256: triggerDb.sha256 })
        : undefined,
    [triggerDb],
  );

  const overlayJson = useMemo(
    () => (active ? buildOverlayConfigJson(active.id, active.project.assets, NOMINAL_MARKER_WIDTH) : undefined),
    [active],
  );

  const retryAll = () => {
    setReloadToken((t) => t + 1);
    loadList();
  };

  if (permission === 'checking') {
    return <View style={styles.center} />;
  }

  if (permission !== 'granted') {
    const blocked = permission === 'blocked';
    return (
      <View style={styles.center}>
        <Text style={styles.title}>Cần quyền camera</Text>
        <Text style={styles.errorText}>
          {blocked
            ? 'Bạn đã tắt quyền camera cho app. Hãy bật lại trong Cài đặt để quét ảnh.'
            : 'App cần camera để nhận diện ảnh và hiển thị nội dung AR.'}
        </Text>
        <TouchableOpacity style={styles.button} onPress={blocked ? () => Linking.openSettings() : requestCamera}>
          <Text style={styles.buttonText}>{blocked ? 'Mở Cài đặt' : 'Cấp quyền'}</Text>
        </TouchableOpacity>
      </View>
    );
  }

  if (phase === 'loading') {
    return (
      <View style={styles.center}>
        <ActivityIndicator size="large" color="#ffffff" />
        <Text style={styles.text}>{loadingMsg}</Text>
        {slowHint && (
          <Text style={styles.hint}>Lần mở đầu tiên máy chủ có thể cần tới 1 phút để khởi động.</Text>
        )}
      </View>
    );
  }

  if (phase === 'error') {
    return (
      <View style={styles.center}>
        <Text style={styles.title}>Không tải được dữ liệu</Text>
        <Text style={styles.errorText}>{errorMsg}</Text>
        <TouchableOpacity style={styles.button} onPress={retryAll}>
          <Text style={styles.buttonText}>Thử lại</Text>
        </TouchableOpacity>
        {/* Chỉ bản debug: biết ngay app đang gọi backend nào (API_URL trong .env) */}
        {__DEV__ && <Text style={styles.devInfo}>Backend: {API_BASE_URL}</Text>}
      </View>
    );
  }

  if (phase === 'empty') {
    return (
      <View style={styles.center}>
        <Text style={styles.title}>Chưa có nội dung AR</Text>
        <Text style={styles.errorText}>Hiện chưa có ảnh nào để quét. Hãy quay lại sau nhé.</Text>
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
        triggerDbJson={triggerDbJson}
        overlayJson={overlayJson}
        reloadToken={reloadToken}
      />

      {arStatus.state === 'ready' && !anyTracked && (
        <View style={styles.overlay} pointerEvents="none">
          <ScanFrame />
          <Text style={styles.overlayText}>Hướng camera vào ảnh để xem nội dung AR</Text>
        </View>
      )}

      {arStatus.state === 'ready' && anyTracked && (
        <View style={[styles.badge, !activeTracked && styles.badgeLoading]} pointerEvents="none">
          {!activeTracked && <ActivityIndicator size="small" color="#ffffff" style={styles.badgeSpinner} />}
          <Text style={styles.badgeText} numberOfLines={1}>
            {activeTracked ? active!.project.name : 'Đang tải nội dung...'}
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
  title: { color: '#fff', fontSize: 20, fontWeight: '700', textAlign: 'center', marginBottom: 8 },
  text: { color: '#fff', marginTop: 12, fontSize: 16, textAlign: 'center' },
  hint: { color: 'rgba(255,255,255,0.6)', marginTop: 8, fontSize: 14, textAlign: 'center', maxWidth: 280 },
  devInfo: { color: 'rgba(255,255,255,0.45)', marginTop: 24, fontSize: 12, textAlign: 'center' },
  errorText: { color: 'rgba(255,255,255,0.85)', fontSize: 16, textAlign: 'center', marginBottom: 20, maxWidth: 300 },
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
  overlayText: {
    marginTop: 24,
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
    flexDirection: 'row',
    alignItems: 'center',
    maxWidth: '85%',
    backgroundColor: 'rgba(40,180,99,0.9)',
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 20,
  },
  badgeLoading: { backgroundColor: 'rgba(0,0,0,0.6)' },
  badgeSpinner: { marginRight: 8 },
  badgeError: { top: 110, backgroundColor: 'rgba(200,60,60,0.9)' },
  badgeText: { color: '#fff', fontWeight: '600' },
});

export default ARScanScreen;