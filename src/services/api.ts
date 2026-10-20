import { PublicProject, PublishedTrigger } from '../types/ar';

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
 * Danh sách trigger image của mọi project đã publish — dùng để dựng database nhận diện, app tự nhận ra
 * project khi người dùng soi vào ảnh. Map backend: GET /public/projects (scan.controller.ts).
 */
export async function fetchPublishedTriggers(): Promise<PublishedTrigger[]> {
  const res = await fetch(`${API_BASE_URL}/public/projects`);
  if (!res.ok) {
    throw new Error(`Fetch danh sách project failed: ${res.status}`);
  }
  return res.json();
}