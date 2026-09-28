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

interface ArCameraViewProps extends ViewProps {
  /** URL công khai của trigger image (project.triggerImageUrl từ backend) */
  targetImageUrl: string;
  targetName?: string;
  /** Chiều rộng "danh nghĩa" dùng để ước lượng pose, đơn vị mét — khớp NOMINAL_MARKER_WIDTH cũ */
  physicalWidth?: number;
  overlayBoxSize?: number;
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
