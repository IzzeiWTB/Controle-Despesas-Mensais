const { defineConfig } = require('@playwright/test');
module.exports = defineConfig({
  testDir: './tests',
  workers: 1,
  timeout: 30000,
  use: { baseURL: 'http://127.0.0.1:18765', channel: 'chrome', trace: 'retain-on-failure' },
  webServer: { command: 'node scripts/test-server.mjs', url: 'http://127.0.0.1:18765', timeout: 60000, reuseExistingServer: false },
  reporter: 'list'
});
