import { requireNativeComponent, DeviceEventEmitter, ViewProps, EmitterSubscription } from 'react-native';

export interface ArAnchorUpdatedEvent {
  name: string;
  tx: number;
  ty: number;
  tz: number;
  qx: number;
  qy: number;
  qz: number;
  qw: number;
}

export type ArStatusState = 'loading' | 'ready' | 'error';

export interface ArStatusEvent {
  state: ArStatusState;
  message?: string;
}

interface ArCameraViewProps extends ViewProps {
  /** JSON string mảng [{ name: projectId, url: triggerImageUrl }] — tất cả ảnh cần nhận diện */
  targetsJson?: string;
  /** JSON string { target: projectId, assets: [...] } — overlay của project đang active (buildOverlayConfigJson) */
  overlayJson?: string;
  /** Đổi số này để native dựng lại database + session (nút "Thử lại") */
  reloadToken?: number;
  /** Chiều rộng "danh nghĩa" dùng để ước lượng pose, đơn vị mét */
  physicalWidth?: number;
  /** Debug: bắn event pose mỗi frame (nặng bridge) */
  emitPose?: boolean;
}

export const ArCameraView = requireNativeComponent<ArCameraViewProps>('ArCameraView');

export function addAnchorFoundListener(cb: (e: { name: string }) => void): EmitterSubscription {
  return DeviceEventEmitter.addListener('ArAnchorFound', cb);
}

export function addAnchorUpdatedListener(cb: (e: ArAnchorUpdatedEvent) => void): EmitterSubscription {
  return DeviceEventEmitter.addListener('ArAnchorUpdated', cb);
}

export function addAnchorRemovedListener(cb: (e: { name: string }) => void): EmitterSubscription {
  return DeviceEventEmitter.addListener('ArAnchorRemoved', cb);
}

export function addStatusListener(cb: (e: ArStatusEvent) => void): EmitterSubscription {
  return DeviceEventEmitter.addListener('ArStatus', cb);
}