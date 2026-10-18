const { defineConfig } = require('@playwright/test');

module.exports = defineConfig({
  testDir: './tests/browser',
  workers: 1,
  use: {
    baseURL: 'http://127.0.0.1:18082',
    launchOptions: { executablePath: process.env.CHROME_PATH || '/usr/bin/google-chrome' },
    screenshot: 'only-on-failure'
  },
  webServer: {
    command: 'java -jar target/ocr-laundry.jar serve 18082 samples fifo 2',
    url: 'http://127.0.0.1:18082',
    reuseExistingServer: false
  }
});
