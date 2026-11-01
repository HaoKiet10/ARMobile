# ARMobile

App quét AR cho end-user — React Native 0.86 (Android) + native module ARCore tự viết (`android/app/src/main/java/com/armobile/artracking/`). Đưa camera vào ảnh trigger của project đã publish trên web designer tool là nội dung AR (model `.glb`, ảnh, video) hiện lên.

Backend, web và tài liệu kiến trúc nằm ở repo chính [`Augmented-Reality`](https://github.com/HaoKiet10/Augmented-Reality) (xem README + thư mục `wiki/`).

Tài liệu chi tiết của app nằm trong [`wiki/`](wiki/Index.md) (mở bằng Obsidian để xem graph).

## Yêu cầu

- Node >= 22.11
- **pnpm** (ghim qua `packageManager` trong `package.json`) — không dùng npm/yarn
- JDK 17, Android SDK
- Máy Android hỗ trợ ARCore

## Chạy

```sh
pnpm install
pnpm start                 # Metro
# cửa sổ khác:
adb reverse tcp:8081 tcp:8081
pnpm android
```

```sh
pnpm lint
pnpm test
```

## Chọn backend (`.env`)

App gọi backend production mặc định. Muốn trỏ sang backend khác (vd backend local đang dev):

```sh
cp .env.example .env       # rồi bỏ comment 1 dòng API_URL
pnpm start --reset-cache   # BẮT BUỘC sau mỗi lần đổi .env (giá trị được nhúng lúc bundle)
```

| Trường hợp | `API_URL` | Thêm |
|---|---|---|
| Production (mặc định) | bỏ trống / không có `.env` | |
| Backend local, điện thoại cắm USB | `http://localhost:3000` | `adb reverse tcp:3000 tcp:3000` |
| Backend local qua Wi-Fi | `http://<IP LAN của máy>:3000` | cùng mạng; mở cổng 3000 trên Windows Firewall |

- `.env` không được commit (đã có trong `.gitignore`); mẫu ở `.env.example`.
- `http://` chỉ chạy ở bản **debug** (Android chặn cleartext ở bản release) — bản release luôn dùng `https://`.
- Bản debug hiện URL backend đang dùng ở màn lỗi "Không tải được dữ liệu".
- Có thể đặt qua biến môi trường thay cho file, vd CI: `API_URL=https://... pnpm android`.
- Code: `src/config.ts` (`API_BASE_URL`), cấu hình plugin ở `babel.config.js`.

## Lưu ý về pnpm

React Native cần `node_modules` dạng phẳng: `pnpm-workspace.yaml` đặt `nodeLinker: hoisted`. Đừng xoá dòng này — layout mặc định (isolated) làm ESLint không tìm thấy plugin và dễ vỡ autolinking.

## Cấu trúc

| Đường dẫn | Nội dung |
|---|---|
| `src/screens/ARScanScreen.tsx` | Màn quét: quyền camera, tải database nhận diện, chọn project đang nhìn thấy |
| `src/config.ts` | `API_BASE_URL` — backend app gọi tới (`API_URL` trong `.env`) |
| `src/services/api.ts` | Gọi backend (`/public/trigger-db`, `/public/projects/:id`) |
| `src/utils/arAssets.ts` | Đổi toạ độ editor sang toạ độ marker ARCore |
| `src/native/ArTracking.ts` | Binding JS cho `ArCameraView` native |
| `android/.../artracking/` | ARCore session, render overlay, `.glb` |

Các lỗi hay gặp khi setup máy mới: xem mục "Setup máy mới" trong README repo chính.
