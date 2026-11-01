const path = require('path');

module.exports = {
  presets: ['module:@react-native/babel-preset'],
  plugins: [
    // Đọc .env lúc bundle (xem src/config.ts). Metro tự bỏ cache khi .env đổi (cacheVersion trong
    // metro.config.js), nhưng vẫn phải khởi động lại Metro vì config chỉ được đọc lúc start.
    [
      'module:react-native-dotenv',
      {
        moduleName: '@env',
        // Đường dẫn tuyệt đối: Metro/Gradle chạy từ thư mục khác vẫn đọc đúng .env của app
        path: path.join(__dirname, '.env'),
        allowlist: ['API_URL'],
        allowUndefined: true,
      },
    ],
  ],
};
