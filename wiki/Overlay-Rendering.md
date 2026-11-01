---
title: Overlay-Rendering
tags: [mobile, native, rendering]
---

# Overlay-Rendering

`OverlayRenderer.kt` vẽ asset của project active lên marker, dùng **chung GL context và cùng frame** với camera ([[Native-ArCameraView]]), nên không có độ trễ giữa hình camera và nội dung.

## Vòng đời asset
- `setAssets(specs)` (gọi khi prop `overlayJson` đổi): thay toàn bộ overlay; asset cũ được giải phóng trên GL thread.
- Tải dữ liệu ở **thread nền**, chỉ upload texture/buffer trên GL thread (qua `queueEvent`).
- Cờ `released` trên từng item: asset bị thay giữa lúc đang tải thì bỏ kết quả.
- Chỉ vẽ item đã `ready`; asset tải lỗi chỉ ghi log (`adb logcat -s OverlayRenderer`), không báo lên JS.

| Loại | Cách tải | Cách vẽ |
|---|---|---|
| `image` | `HttpURLConnection` → `BitmapFactory`, thu nhỏ ≤ 2048 px | Quad 1×1 có texture 2D, lật trục v |
| `video` | `MediaPlayer` (lặp vô hạn) → `SurfaceTexture` → external OES texture | Quad có texture OES; cập nhật frame khi `onFrameAvailable` |
| `model` | Tải toàn bộ byte (timeout đọc 60 s) → `GlbParser.parse` | `GlbRenderer` ([[GLB-Support]]) |

Video không cần thêm dependency nào. Chỉ phát khi marker đang được track thật (`setActive(true)`); mất track hoặc app vào nền thì tạm dừng.

## Vẽ
- Bật depth test và blend `ONE, ONE_MINUS_SRC_ALPHA` (bitmap Android upload dạng premultiplied alpha).
- Ma trận model và quy ước trục: [[Coordinate-Conversion]].
- Quad đơn vị nằm trong mặt XY, tâm ở gốc; chiều cao chia cho `aspect` của ảnh/video để giữ tỉ lệ.

## Nền camera
`BackgroundRenderer.kt` vẽ camera feed toàn màn hình bằng external OES texture và gọi `updateTexCoords(frame)` **trước** mỗi lần vẽ — thiếu bước này hình sẽ bị nghiêng/lật khi xoay máy.
