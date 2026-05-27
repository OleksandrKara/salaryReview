import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright tests assume the full Docker stack is up:
 *
 *   docker compose up -d --build
 *
 * Frontend at http://localhost:3000, backend at http://localhost:8080.
 */
export default defineConfig({
  testDir: './tests',
  globalSetup: require.resolve('./tests/globalSetup'),
  fullyParallel: false,            // tests share DB; keep order deterministic
  workers: 1,                      // ditto
  retries: process.env.CI ? 1 : 0,
  reporter: [['list'], ['html', { open: 'never' }]],

  use: {
    baseURL: process.env.PW_BASE_URL ?? 'http://localhost:3000',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },

  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
});
