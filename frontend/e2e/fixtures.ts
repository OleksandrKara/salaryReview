import type { APIRequestContext, Page } from '@playwright/test';

// Phase 6.5 — shared setup helpers for the e2e specs. All of these hit the running app's own
// HTTP surface (through the Next.js proxy, same as a real browser), never the backend directly,
// so a fixture failure surfaces the same way a real user's would.

export const BOOTSTRAP_OWNER_USERNAME = process.env.E2E_OWNER_USERNAME ?? 'owner';
export const BOOTSTRAP_OWNER_PASSWORD = process.env.E2E_OWNER_PASSWORD ?? 'changeme';

/** Logs in via the real login form (not the API directly) — exercises the same path a user does,
 * and leaves the browser context holding the resulting session cookies for the rest of the test.
 * The form lives in a modal (see Landing.tsx) opened by any `[data-signin]` element in the
 * injected landing markup — click one first, then fill the real React modal fields by label. */
export async function loginViaUi(page: Page, username: string, password: string): Promise<void> {
  await page.goto('/');
  // Several [data-signin] triggers exist (desktop nav, mobile nav, hero CTAs, footer) — most are
  // hidden depending on viewport/scroll position. :visible picks whichever one Playwright's
  // default viewport actually shows, rather than hardcoding the position of one specific trigger.
  await page.locator('[data-signin]:visible').first().click();
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(password);
  await page.locator('button[type="submit"]', { hasText: /sign in/i }).click();
  await page.waitForURL((url) => url.pathname !== '/', { timeout: 10_000 });
  // Every account's first-ever page load hits the one-time "Choose your language" modal
  // (LanguagePrompt) — it fetches /api/me in its own useEffect, so it can appear a beat after
  // navigation settles, not immediately. Wait for it (bounded) rather than checking instantaneous
  // visibility, which would race the fetch and wrongly conclude it's never showing.
  const english = page.getByRole('button', { name: 'English' });
  const appeared = await english
    .waitFor({ state: 'visible', timeout: 5_000 })
    .then(() => true)
    .catch(() => false);
  if (appeared) {
    await english.click();
    await page.waitForLoadState('networkidle');
  }
}

/** Creates a second business (+ its own single-membership OWNER) via the platform-admin API,
 * using the given request context's existing session (must already be the bootstrap owner —
 * the only platform_admin on a fresh instance, per OwnerBootstrap). Idempotent-ish: a shortCode
 * collision throws, so callers should pass a fresh one per test run (see randomSuffix). */
export async function createSecondBusiness(
  request: APIRequestContext,
  opts: { name: string; shortCode: string; ownerUsername: string; ownerPassword: string },
): Promise<{ id: number }> {
  const res = await request.post('/api/platform/businesses', {
    data: {
      name: opts.name,
      shortCode: opts.shortCode,
      timezone: 'America/Los_Angeles',
      ownerUsername: opts.ownerUsername,
      ownerPassword: opts.ownerPassword,
    },
  });
  if (!res.ok()) {
    throw new Error(`createSecondBusiness failed: ${res.status()} ${await res.text()}`);
  }
  return res.json();
}

/** A short, random-enough suffix so repeated local test runs don't collide on shortCode/username
 * uniqueness constraints against a database that isn't wiped between runs. */
export function randomSuffix(): string {
  return Math.random().toString(36).slice(2, 8);
}
