---
title: Coordinate-Conversion
tags: [mobile, ar, math]
---

# Coordinate-Conversion

`src/utils/arAssets.ts` (phần JS) và `OverlayRenderer.draw` (phần native) cùng nhau đặt asset của designer lên marker ARCore.

## Đơn vị: tương đối theo bề rộng trigger image
Trong editor web, position/scale là **tỉ lệ so với bề rộng ảnh trigger**, không phải mét: `scale = 1` là rộng bằng 100% ảnh, `position = 0` là tâm ảnh. Mobile nhân với một bề rộng danh nghĩa:

- `NOMINAL_MARKER_WIDTH = 0.15` (mét) trong `ARScanScreen.tsx`.
- **Phải khớp** `NOMINAL_MARKER_WIDTH_M` trong `backend/.../trigger-db.service.ts`: server ghi đúng bề rộng này vào database nhận diện để ARCore ước lượng pose. Lệch nhau thì asset bị sai tỉ lệ và trôi khỏi ảnh. Đổi giá trị ở backend làm `version` đổi, mọi máy tải lại database ([[Trigger-DB-Cache]]).

Vì cả pose lẫn asset đều tính theo cùng 0.15 m, overlay vẫn đúng tỉ lệ dù ảnh in ra to hay nhỏ.

## Đổi hệ trục
| Hệ | Mặt phẳng marker | Trục |
|---|---|---|
| Editor (A-Frame) | XY | x phải, y lên, z ra khỏi mặt ảnh |
| Marker ARCore | XZ | x phải, y ra khỏi mặt ảnh, z xuống dưới |

Gốc toạ độ là **tâm** marker ở cả hai hệ.

- **Position** (JS, `editorToMarkerPosition`): `(x, y, z) → (x·w, z·w, −y·w)` với `w` = bề rộng danh nghĩa. Thiếu vector → `[0,0,0]`.
- **Scale** (JS, `relative`): `(x·w, y·w, z·w)`; thiếu → `[1,1,1]`.
- **Rotation**: giữ nguyên độ, đổi hệ ở native.

Ma trận model ở native (`OverlayRenderer.draw`):

```text
model = pose · T(position) · C · Ry · Rx · Rz · S
```

- `C` = xoay −90° quanh X, đổi hệ editor sang hệ marker; nhờ vậy quad/model "quay mặt về +Z" trong editor nằm đúng trên mặt ảnh.
- Thứ tự `Ry · Rx · Rz` khớp Euler `YXZ` của A-Frame (`aframe/src/components/rotation.js`). Đổi thứ tự là asset xoay khác với editor.
- Ảnh/video: `scale.x` là bề rộng quad (mét), chiều cao = `scale.y / aspect` để giữ tỉ lệ khung hình.
- Model `.glb`: scale nhân thẳng lên kích thước gốc của file (glTF tính bằng mét) — model to 2 m với `scale = 1` sẽ ra 0.15 × 2 = 0.3 m.

## Phân loại asset (`getKind`)
Theo MIME trước, rồi đuôi file:
- `image/*` hoặc `png jpg jpeg gif webp` → `image`
- `video/*` hoặc `mp4 mov m4v webm` → `video`
- `model/gltf-binary` hoặc `glb` → `model`
- còn lại (`.gltf` dạng text, `.obj`, `.vrx`…) → bị bỏ qua, không gửi xuống native ([[GLB-Support]])

`buildOverlayConfigJson(target, assets, w)` gói thành `{"target": projectId, "assets": [{id, url, kind, position, rotation, scale}]}` cho prop `overlayJson` ([[Native-ArCameraView]]).
