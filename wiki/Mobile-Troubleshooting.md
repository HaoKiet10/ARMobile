---
title: Mobile-Troubleshooting
tags: [mobile, ops]
---

# Mobile-Troubleshooting

Log native: `adb logcat -s ArCameraView OverlayRenderer`.

## Setup và build
| Triệu chứng | Nguyên nhân | Cách xử lý |
|---|---|---|
| Gradle lỗi lạ khi build | JDK sai version | Dùng JDK 17; đặt `org.gradle.java.home` trong `android/gradle.properties` ([[Mobile-Setup]]) |
| Báo nhầm "This device does not support AR" | Thiếu meta-data `com.google.ar.core` | Giữ `<meta-data android:name="com.google.ar.core" android:value="required" />` trong `<application>` của `AndroidManifest.xml` |
| `requireNativeComponent: "ArCameraView" was not found` | Native view không autolink | `ArTrackingPackage()` phải được thêm vào `packageList` của `reactHost` trong `MainApplication.kt` (không phải `getPackages()` cũ) ([[Native-ArCameraView]]) |
| `adb` báo `protocol fault`, cổng 5037 bị chiếm | Extension React Native Tools của VS Code chiếm cổng ADB | Đóng VS Code rồi chạy lại |
| ESLint không tìm thấy plugin, autolink vỡ | Mất `nodeLinker: hoisted` | Khôi phục dòng đó trong `pnpm-workspace.yaml` |

## Thông báo trong app
| Thông báo | Ý nghĩa | Cách xử lý |
|---|---|---|
| "Máy chủ đang chuẩn bị dữ liệu nhận diện..." | `/public/trigger-db` trả 503: server mới khởi động chưa dựng xong database, hoặc server thiếu `arcoreimg` | Đợi; nếu kéo dài, xem log backend ([[API-Client]]) |
| "Lần mở đầu tiên máy chủ có thể cần tới 1 phút" | Render free đang thức dậy | Đợi; app tự thử lại 4 lần ([[Scan-Flow]]) |
| "Chưa có nội dung AR" | Chưa project nào publish có trigger image | Publish project trên web |
| "Nội dung này đã bị gỡ hoặc chưa được công bố" | Project bị unpublish sau khi database được dựng | Bình thường; database sẽ dựng lại sau vài giây |
| "Cần cài hoặc cập nhật Google Play Services for AR" | ARCore chưa cài hoặc quá cũ | Cài/cập nhật trên Google Play |
| "Thiết bị này không hỗ trợ ARCore" | Máy không có trong danh sách ARCore | Đổi máy |
| "File tải về bị hỏng (sha256 không khớp)" | Tải dở, hoặc server vừa thay file | Bấm "Thử lại" ([[Trigger-DB-Cache]]) |
| "Database nhận diện không đọc được" | Cache hỏng và tải lại vẫn hỏng | Xoá dữ liệu app; kiểm tra file trên server |

## Nhận diện và hiển thị
| Triệu chứng | Nguyên nhân thường gặp |
|---|---|
| Không nhận ra ảnh | Ảnh trigger ít chi tiết (server cảnh báo điểm `eval-img` < 75 hoặc loại ảnh khỏi database); ánh sáng yếu; ảnh bị chói |
| Asset sai tỉ lệ hoặc trôi khỏi ảnh | `NOMINAL_MARKER_WIDTH` mobile lệch với backend ([[Coordinate-Conversion]]) |
| Asset xoay khác editor | Thứ tự Euler không còn là YXZ ([[Coordinate-Conversion]]) |
| Model không hiện | `.gltf`/`.obj`, hoặc `.glb` nén Draco/meshopt — xem warnings trong log ([[GLB-Support]]) |
| Nội dung tắt khi nghiêng máy xa | Chỉ vẽ khi `FULL_TRACKING`, cố ý bỏ extended tracking ([[Native-ArCameraView]]) |

Gặp lỗi mới thì bổ sung vào bảng.
