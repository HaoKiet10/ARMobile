import { ARAsset, SpatialVector } from '../types/ar';

const IMAGE_EXT = new Set(['png', 'jpg', 'jpeg', 'gif', 'webp']);
const VIDEO_EXT = new Set(['mp4', 'mov', 'm4v', 'webm']);

type Kind = 'image' | 'video' | 'model' | 'unsupported';

function getKind(asset: ARAsset): Kind {
  const mime = (asset.fileType ?? '').toLowerCase();
  if (mime.startsWith('image/')) return 'image';
  if (mime.startsWith('video/')) return 'video';
  const ext = asset.filename.toLowerCase().split('.').pop() ?? '';
  if (IMAGE_EXT.has(ext)) return 'image';
  if (VIDEO_EXT.has(ext)) return 'video';
  if (mime === 'model/gltf-binary' || ext === 'glb') return 'model';
  return 'unsupported'; // .gltf text / .obj / .vrx chưa hỗ trợ trên mobile
}

// Quy ước giữ nguyên bản react-viro cũ: có vector -> nhân với marker width
// (designer nhập tỉ lệ, 1 = 100% bề rộng marker); không có -> dùng fallback nguyên.
function relative(
  vec: SpatialVector | undefined,
  fallback: [number, number, number],
  width: number,
): [number, number, number] {
  if (!vec) return fallback;
  return [vec.x * width, vec.y * width, vec.z * width];
}

/**
 * Đổi vị trí từ hệ editor (marker = mặt phẳng XY, x phải, y lên, z ra khỏi mặt) sang hệ marker
 * của ARCore (marker = mặt phẳng XZ, x phải, y ra khỏi mặt, z xuống dưới), rồi nhân marker width
 * (editor nhập tỉ lệ, 1 = 100% bề rộng marker): (x, y, z) -> (x, z, -y).
 * Gốc toạ độ = TÂM marker ở cả 2 hệ. Nếu editor đặt gốc ở góc trên-trái marker thì phải
 * trừ offset tâm ở đây.
 */
function editorToMarkerPosition(vec: SpatialVector | undefined, width: number): [number, number, number] {
  if (!vec) return [0, 0, 0];
  return [vec.x * width, vec.z * width, -vec.y * width];
}

/** Tạo JSON cho prop assetsJson của ArCameraView. Ảnh, video và model .glb; định dạng khác bị bỏ qua. */
export function buildOverlayAssetsJson(assets: ARAsset[], markerWidth: number): string {
  const list = assets
    .map((a) => ({ a, kind: getKind(a) }))
    .filter((x) => x.kind !== 'unsupported')
    .map(({ a, kind }) => ({
      id: a.id,
      url: a.url,
      kind,
      position: editorToMarkerPosition(a.transform?.position, markerWidth),
      scale: relative(a.transform?.scale, [1, 1, 1], markerWidth),
      rotation: a.transform?.rotation
        ? [a.transform.rotation.x, a.transform.rotation.y, a.transform.rotation.z]
        : [0, 0, 0],
    }));
  return JSON.stringify(list);
}