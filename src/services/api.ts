import { PublicProject, TriggerDb } from '../types/ar';

// TODO: đổi thành URL backend thật (staging/production)
const API_BASE_URL = 'https://ar-backend-vkdz.onrender.com';

/**
 * Lấy project đã publish theo ID — dùng cho mobile app end-user quét.
 * Route KHÔNG cần token (public), map đúng backend/src/modules/project/scan.controller.ts
 */
export async function fetchPublicProject(projectId: string): Promise<PublicProject> {
  const res = await fetch(`${API_BASE_URL}/public/projects/${projectId}`);
  if (!res.ok) {
    if (res.status === 404) {
      throw new Error('Project không tồn tại hoặc chưa được publish');
    }
    throw new Error(`Fetch project failed: ${res.status}`);
  }
  return res.json();
}
/**
 * Database nhận diện ảnh trigger (ARCore .imgdb) của mọi project đã publish, do server dựng sẵn — app tự
 * nhận ra project khi người dùng soi vào ảnh. Map backend: GET /public/trigger-db (scan.controller.ts).
 */
export async function fetchTriggerDb(): Promise<TriggerDb> {
  const res = await fetch(`${API_BASE_URL}/public/trigger-db`);
  if (!res.ok) {
    if (res.status === 503) {
      throw new Error('Server đang chuẩn bị dữ liệu nhận diện, thử lại sau ít phút');
    }
    throw new Error(`Fetch database nhận diện failed: ${res.status}`);
  }
  return res.json();
}