const { defineConfig } = require('@playwright/test');
module.exports = defineConfig({
  testDir: './tests-android',
  workers: 1,
  timeout: 120000,
  use: { baseURL: 'http://127.0.0.1:18765' },
  webServer: { command: 'node scripts/test-server.mjs', url: 'http://127.0.0.1:18765', timeout: 60000, reuseExistingServer: false },
  reporter: 'list'
});
