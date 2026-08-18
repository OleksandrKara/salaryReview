import { expect, test } from '@playwright/test';
import {
  BOOTSTRAP_OWNER_USERNAME,
  BOOTSTRAP_OWNER_PASSWORD,
  createSecondBusiness,
  loginViaUi,
  randomSuffix,
} from './fixtures';

// Phase 6.5 (multi-tenant-salon-platform), design.md D12: AdminMenu's business-switcher row
// should be plain, non-interactive text for the ~100% single-membership case (no visual change
// from before the switcher existed), and a real <select> only when the caller's own /api/me
// businesses array has more than one entry — see AdminMenu.tsx and MeController's own doc.
//
// Requires a fresh (or reset) app instance whose only existing account is the bootstrap owner
// (business 1, platform_admin) — see e2e/README.md for how to seed one. Creates a second business
// itself via the platform-admin API, so it's self-contained beyond that starting state.

test.describe('business switcher (design.md D12)', () => {
  const suffix = randomSuffix();
  const secondOwnerUsername = `e2eowner_${suffix}`;
  const secondOwnerPassword = 'e2e-test-password-1';

  test.beforeAll(async ({ request }) => {
    // Authenticate as the bootstrap owner so the platform-admin API call below carries a real
    // session — request contexts don't share cookies with a browser Page, so this is a
    // standalone login just for fixture setup, not reused by either test below.
    const loginRes = await request.post('/api/login', {
      data: { username: BOOTSTRAP_OWNER_USERNAME, password: BOOTSTRAP_OWNER_PASSWORD },
    });
    if (!loginRes.ok()) {
      throw new Error(
        `Fixture setup failed to log in as bootstrap owner '${BOOTSTRAP_OWNER_USERNAME}' — ` +
          `is E2E_OWNER_USERNAME/E2E_OWNER_PASSWORD set correctly for this environment? (${loginRes.status()})`,
      );
    }
    await createSecondBusiness(request, {
      name: `E2E Second Business ${suffix}`,
      shortCode: `e2ebiz${suffix}`,
      ownerUsername: secondOwnerUsername,
      ownerPassword: secondOwnerPassword,
    });
  });

  test('platform_admin (2+ businesses) sees a real dropdown with both names', async ({ page }) => {
    await loginViaUi(page, BOOTSTRAP_OWNER_USERNAME, BOOTSTRAP_OWNER_PASSWORD);
    await page.getByRole('button', { name: 'Menu' }).click();

    const switcher = page.locator('select');
    await expect(switcher).toBeVisible();
    const optionTexts = await switcher.locator('option').allTextContents();
    expect(optionTexts.length).toBeGreaterThanOrEqual(2);
    expect(optionTexts).toContain(`E2E Second Business ${suffix}`);
  });

  test('single-membership owner sees no switcher at all — no visual change', async ({ page }) => {
    await loginViaUi(page, secondOwnerUsername, secondOwnerPassword);
    await page.getByRole('button', { name: 'Menu' }).click();

    // The menu itself must still open normally (role-scoped links render) — only the switcher
    // row is absent, not the whole menu.
    await expect(page.getByRole('menu')).toBeVisible();
    await expect(page.locator('select')).toHaveCount(0);
  });

  test('platform_admin can actually switch business and see the new context take effect', async ({ page }) => {
    await loginViaUi(page, BOOTSTRAP_OWNER_USERNAME, BOOTSTRAP_OWNER_PASSWORD);
    await page.getByRole('button', { name: 'Menu' }).click();

    const switcher = page.locator('select');
    // AdminMenu's switchTo() awaits the API call, then calls window.location.reload() — the
    // reload's own navigation only starts after selectOption()'s change event has already
    // resolved, so waiting on the resulting `load` event (not just networkidle afterward) is what
    // actually catches it without racing a subsequent goto() against it.
    await Promise.all([
      page.waitForEvent('load', { timeout: 10_000 }),
      switcher.selectOption({ label: `E2E Second Business ${suffix}` }),
    ]);

    await page.goto('/admin/users');
    // The freshly created business's own owner is its only user — the platform_admin's own
    // account (created on business 1) must not appear once switched into business 2's context.
    await expect(page.getByText(secondOwnerUsername)).toBeVisible();
    await expect(page.getByText(BOOTSTRAP_OWNER_USERNAME, { exact: true })).toHaveCount(0);
  });
});
