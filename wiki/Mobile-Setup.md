---
title: Mobile-Setup
tags: [mobile, setup]
---

# Mobile-Setup

## Yêu cầu
- Node ≥ 22.11
- **pnpm** (ghim qua `packageManager` trong `package.json`), không dùng npm/yarn
- **JDK 17** (không phải bản mới hơn), Android SDK (`compileSdk 36`, `minSdk 24`)
- Máy Android thật hỗ trợ ARCore, có "Google Play Services for AR". Emulator không dùng được camera AR.

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

App gọi backend production trên Render mặc định ([[API-Client]]). Muốn dùng backend local: `cp .env.example .env`, đặt `API_URL=http://localhost:3000` (cắm USB, chạy thêm `adb reverse tcp:3000 tcp:3000`) hoặc `http://<IP LAN>:3000` (Wi-Fi), rồi `pnpm start --reset-cache`. Backend local cần `arcoreimg` để dựng database (note `Trigger-Database` ở wiki repo chính).

## pnpm và `nodeLinker: hoisted`
React Native cần `node_modules` dạng phẳng: `pnpm-workspace.yaml` đặt `nodeLinker: hoisted`. Đừng xoá dòng này — layout isolated mặc định làm ESLint không tìm thấy plugin và dễ vỡ autolinking.

## Vì sao là repo riêng
Mobile cần đúng `react@19.2.3` (bản React Native 0.86 đóng gói sẵn) còn frontend web cần `react@^19.2.6`. Chung một pnpm workspace sẽ có 2 bản React, Metro resolve không nhất quán → lỗi `Cannot read property 'useState' of null`. Repo chính vẫn giữ dòng `!mobile` trong `pnpm-workspace.yaml` phòng khi clone app vào trong.

## Test
`__tests__/App.test.tsx` chỉ render `App` một lần (smoke test, preset `@react-native/jest-preset`). Chưa có test cho logic chọn project hay đổi toạ độ ([[Mobile-Roadmap]]).

Lỗi khi setup máy mới: [[Mobile-Troubleshooting]].
