import { API_BASE_URL } from '../config';
import { PublicProject, TriggerDb } from '../types/ar';

// Gói free của Render ngủ khi không có truy cập: request đầu tiên có thể mất ~50s để server thức dậy.
const REQUEST_TIMEOUT_MS = 70_000;

/** Lỗi gọi API với message đã thân thiện cho người dùng. `retryable` = nên tự thử lại sau ít giây. */
export class ApiError extends Error {
  constructor(message: string, readonly status?: number, readonly retryable = false) {
    super(message);
  }
}

async function request(path: string): Promise<Response> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  try {
    return await fetch(`${API_BASE_URL}${path}`, { signal: controller.signal });
  } catch (err: any) {
    if (err?.name === 'AbortError') {
      throw new ApiError('Máy chủ phản hồi quá lâu. Vui lòng thử lại.', undefined, true);
    }
    throw new ApiError('Không kết nối được máy chủ. Kiểm tra kết nối mạng và thử lại.', undefined, true);
  } finally {
    clearTimeout(timer);
  }
}

function serverError(status: number): ApiError {
  return new ApiError(`Máy chủ đang gặp sự cố (mã ${status}). Vui lòng thử lại sau.`, status, status >= 500);
}

/**
 * Lấy project đã publish theo ID — dùng cho mobile app end-user quét.
 * Route KHÔNG cần token (public), map đúng backend/src/modules/project/scan.controller.ts
 */
export async function fetchPublicProject(projectId: string): Promise<PublicProject> {
  const res = await request(`/public/projects/${projectId}`);
  if (!res.ok) {
    if (res.status === 404) {
      throw new ApiError('Nội dung này đã bị gỡ hoặc chưa được công bố.', 404);
    }
    throw serverError(res.status);
  }
  return res.json();
}

/**
 * Database nhận diện ảnh trigger (ARCore .imgdb) của mọi project đã publish, do server dựng sẵn — app tự
 * nhận ra project khi người dùng soi vào ảnh. Map backend: GET /public/trigger-db (scan.controller.ts).
 */
export async function fetchTriggerDb(): Promise<TriggerDb> {
  const res = await request('/public/trigger-db');
  if (!res.ok) {
    if (res.status === 503) {
      // Server vừa khởi động, đang dựng database nhận diện — thường xong sau vài giây
      throw new ApiError('Máy chủ đang chuẩn bị dữ liệu nhận diện...', 503, true);
    }
    throw serverError(res.status);
  }
  return res.json();
}
