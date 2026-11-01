---
title: Mobile-Roadmap
tags: [planning, mobile]
---

# Mobile-Roadmap

Việc còn thiếu, rút ra từ code hiện tại (11/2026).

## Kỹ thuật
- **iOS chưa có AR**: `ios/` chỉ là template React Native; cần một view ARKit tương đương [[Native-ArCameraView]] (ARKit dùng ảnh tham chiếu riêng, không đọc được `.imgdb`).
- **Test**: mới có smoke test render `App`. Nên thêm unit test cho `arAssets.ts` ([[Coordinate-Conversion]]), logic chọn project active ([[Scan-Flow]]) và test JVM cho `GlbParser` ([[GLB-Support]]).
- Lỗi tải asset (ảnh/video/model hỏng) chỉ ghi log native, người dùng không biết ([[Overlay-Rendering]]).
- Hằng số `NOMINAL_MARKER_WIDTH` nhân đôi ở mobile và backend; nên để backend trả giá trị trong `/public/trigger-db`.

## Tính năng
- Model: animation, nén Draco/meshopt, texture KTX2, shading PBR ([[GLB-Support]]).
- Âm thanh cho video (hiện `MediaPlayer` phát cả tiếng; chưa có nút tắt).
- i18n: mọi chuỗi giao diện đang viết thẳng tiếng Việt.
- Ghi lượt quét (backend chưa có route nhận).
