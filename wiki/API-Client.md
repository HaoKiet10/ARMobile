---
title: API-Client
tags: [mobile, js, api]
---

# API-Client

`src/services/api.ts` + kiểu dữ liệu ở `src/types/ar.ts`. App chỉ gọi 2 route **public** của backend, không cần đăng nhập, không gọi thẳng Supabase (trừ việc tải file theo URL public mà backend trả về).

| Hàm | Route | Trả về |
|---|---|---|
| `fetchTriggerDb()` | `GET /public/trigger-db` | `TriggerDb`: `version`, `url` (null khi chưa project nào publish), `sha256`, `size`, `triggers: {id, name}[]` |
| `fetchPublicProject(id)` | `GET /public/projects/:id` | `PublicProject`: `id`, `name`, `description`, `triggerImageUrl`, `assets: ARAsset[]` |

`ARAsset` gồm `id`, `filename`, `fileType` (MIME), `url`, `transform` (`position`/`rotation`/`scale`, mỗi cái `{x,y,z}`, hoặc `null`). Kiểu phải khớp `scan.controller.ts` phía backend; đổi response ở backend thì sửa cả `types/ar.ts`.

## Cấu hình
- `API_BASE_URL` ở `src/config.ts`: lấy `API_URL` từ `.env` (plugin `react-native-dotenv`, nhúng lúc bundle), bỏ trống thì dùng production `https://ar-backend-vkdz.onrender.com`. Mẫu ở `.env.example`; đổi `.env` xong phải `pnpm start --reset-cache` ([[Mobile-Setup]]).
- Bản debug hiện URL backend đang dùng ở màn lỗi "Không tải được dữ liệu".
- `REQUEST_TIMEOUT_MS = 70_000`: gói free của Render ngủ khi không có truy cập, request đầu có thể mất khoảng 50 s.

## Lỗi
Mọi lỗi được đổi thành `ApiError(message, status?, retryable)` với message tiếng Việt đã thân thiện cho người dùng:

| Tình huống | Message (rút gọn) | `retryable` |
|---|---|---|
| Quá timeout (`AbortError`) | Máy chủ phản hồi quá lâu | có |
| Lỗi mạng khác | Không kết nối được máy chủ | có |
| `trigger-db` trả 503 | Máy chủ đang chuẩn bị dữ liệu nhận diện | có |
| `projects/:id` trả 404 | Nội dung đã bị gỡ hoặc chưa được công bố | không |
| HTTP khác | Máy chủ đang gặp sự cố (mã N) | chỉ khi ≥ 500 |

`retryable` quyết định [[Scan-Flow]] có tự thử lại hay báo lỗi ngay. Ý nghĩa 503 phía server: [[Mobile-Troubleshooting]].
