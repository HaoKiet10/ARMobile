import { API_URL } from '@env';

// Backend production — dùng khi không có .env (bản release, máy mới clone)
const PRODUCTION_API_URL = 'https://ar-backend-vkdz.onrender.com';

/** URL backend app gọi tới. Đặt API_URL trong .env để trỏ sang backend khác (xem .env.example). */
export const API_BASE_URL = (API_URL?.trim() || PRODUCTION_API_URL).replace(/\/+$/, '');
