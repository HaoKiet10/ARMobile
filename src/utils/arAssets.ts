import { ARAsset, SpatialVector } from '../types/ar';

const IMAGE_EXT = new Set(['png', 'jpg', 'jpeg', 'gif', 'webp']);
const VIDEO_EXT = new Set(['mp4', 'mov', 'm4v', 'webm']);

type Kind = 'image' | 'video' | 'model';

function getKind(asset: ARAsset): Kind {
  const mime = (asset.fileType ?? '').toLowerCase();
  if (mime.startsWith('image/')) return 'image';
  if (mime.startsWith('video/')) return 'video';
  const ext = asset.filename.toLowerCase().split('.').pop() ?? '';
  if (IMAGE_EXT.has(ext)) return 'image';
  if (VIDEO_EXT.has(ext)) return 'video';
  return 'model';
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

/** Tạo JSON cho prop assetsJson của ArCameraView. Chỉ ảnh + video; model 3D bỏ qua (làm sau). */
export function buildOverlayAssetsJson(assets: ARAsset[], markerWidth: number): string {
  const list = assets
    .map((a) => ({ a, kind: getKind(a) }))
    .filter((x) => x.kind !== 'model')
    .map(({ a, kind }) => ({
      id: a.id,
      url: a.url,
      kind,
      position: relative(a.transform?.position, [0, 0, 0], markerWidth),
      scale: relative(a.transform?.scale, [1, 1, 1], markerWidth),
      rotation: a.transform?.rotation
        ? [a.transform.rotation.x, a.transform.rotation.y, a.transform.rotation.z]
        : [0, 0, 0],
    }));
  return JSON.stringify(list);
}