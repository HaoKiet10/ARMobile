// Biến lấy từ .env qua react-native-dotenv (babel.config.js). Thêm biến mới thì thêm cả vào allowlist.
declare module '@env' {
  export const API_URL: string | undefined;
}
