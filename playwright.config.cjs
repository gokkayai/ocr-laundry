const { defineConfig } = require('@playwright/test');
const { existsSync } = require('node:fs');

const executablePath = process.env.PLAYWRIGHT_USE_BUNDLED
  ? undefined
  : process.env.CHROME_PATH || (existsSync('/usr/bin/google-chrome') ? '/usr/bin/google-chrome' : undefined);

module.exports = defineConfig({
  testDir: './tests/browser',
  workers: 1,
  use: {
    baseURL: 'http://127.0.0.1:18082',
    launchOptions: executablePath ? { executablePath } : {},
    screenshot: 'only-on-failure'
  },
  webServer: {
    command: 'java -jar target/ocr-laundry.jar serve 18082 samples fifo 2',
    url: 'http://127.0.0.1:18082',
    reuseExistingServer: false
  }
});
