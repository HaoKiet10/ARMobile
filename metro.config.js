const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { getDefaultConfig, mergeConfig } = require('@react-native/metro-config');

// API_URL (.env, react-native-dotenv) được nhúng lúc Babel transform, mà cache transform của Metro không
// biết .env đổi -> vẫn phục vụ URL cũ. Đưa nội dung .env (và biến môi trường API_URL) vào cacheVersion
// để Metro tự bỏ cache mỗi khi giá trị đổi, không cần nhớ --reset-cache.
function envCacheVersion() {
  let envFile = '';
  try {
    envFile = fs.readFileSync(path.join(__dirname, '.env'), 'utf8');
  } catch {
    // không có .env -> dùng backend production
  }
  return crypto
    .createHash('sha256')
    .update(envFile)
    .update(process.env.API_URL ?? '')
    .digest('hex')
    .slice(0, 16);
}

/**
 * Metro configuration
 * https://reactnative.dev/docs/metro
 *
 * @type {import('@react-native/metro-config').MetroConfig}
 */
const config = {
  cacheVersion: envCacheVersion(),
};

module.exports = mergeConfig(getDefaultConfig(__dirname), config);
