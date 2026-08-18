import { defineConfig, devices } from '@playwright/test';

// Phase 6.5 (multi-tenant-salon-platform). Deliberately NOT wired into GitHub Actions yet — see
// e2e/README.md for why (this app's CI/deploy pipeline is a live production deploy trigger, and
// this session already flagged CI-reliability concerns once tonight; a new, timing-sensitive
// multi-service e2e stage isn't something to bolt onto that pipeline without separate review).
// Run locally against an already-running dev environment (backend + frontend + Postgres) — see
// e2e/README.md for the exact setup. baseURL/BACKEND_URL point at that environment, not this repo's
// own dev server, since Playwright doesn't manage the backend/Postgres lifecycle here.
export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  fullyParallel: false, // the two spec files share one seeded backend; parallel runs would race
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: 'list',
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:13000',
    trace: 'retain-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
});
