import { expect, test } from '@playwright/test';
import {
  BOOTSTRAP_OWNER_USERNAME,
  BOOTSTRAP_OWNER_PASSWORD,
  createSecondBusiness,
  loginViaUi,
  randomSuffix,
} from './fixtures';

// Phase 6.5 (multi-tenant-salon-platform). Exercises the Phase 6.4 /onboarding page end to end:
// a freshly platform-admin-created business's owner connects Square, then invites their first
// manager — the two forms this page combines.
//
// Real Square sandbox credentials are required for the Connect Square step (SquareConnectionService
// validates the token against a real Square call before saving — see SquareConnectionForm's own
// doc — so there's no way to exercise a successful save without one). Same "ships dark until
// configured" convention as every other optional integration in this codebase: skips with a clear
// reason instead of failing when SQUARE_SANDBOX_ACCESS_TOKEN/SQUARE_SANDBOX_LOCATION_ID aren't set,
// rather than either faking success or blocking the rest of the suite on a secret this repo doesn't
// ship. See e2e/README.md for how to provide them locally or in CI.
const SANDBOX_TOKEN = process.env.SQUARE_SANDBOX_ACCESS_TOKEN;
const SANDBOX_LOCATION_ID = process.env.SQUARE_SANDBOX_LOCATION_ID;

test.describe('onboarding flow (design.md, Phase 6.4)', () => {
  test.skip(
    !SANDBOX_TOKEN || !SANDBOX_LOCATION_ID,
    'SQUARE_SANDBOX_ACCESS_TOKEN/SQUARE_SANDBOX_LOCATION_ID not set — see e2e/README.md',
  );

  const suffix = randomSuffix();
  const ownerUsername = `e2eonboard_${suffix}`;
  const ownerPassword = 'e2e-test-password-1';

  test.beforeAll(async ({ request }) => {
    const loginRes = await request.post('/api/login', {
      data: { username: BOOTSTRAP_OWNER_USERNAME, password: BOOTSTRAP_OWNER_PASSWORD },
    });
    if (!loginRes.ok()) {
      throw new Error(`Fixture setup failed to log in as bootstrap owner (${loginRes.status()})`);
    }
    await createSecondBusiness(request, {
      name: `E2E Onboarding Business ${suffix}`,
      shortCode: `e2eonb${suffix}`,
      ownerUsername,
      ownerPassword,
    });
  });

  test('connect Square, then invite a manager, both from /onboarding', async ({ page }) => {
    await loginViaUi(page, ownerUsername, ownerPassword);
    await page.goto('/onboarding');

    await expect(page.getByText('Step 1 — Connect Square')).toBeVisible();
    await expect(page.getByText('Step 2 — Invite your team')).toBeVisible();

    // Step 1: Connect Square with real sandbox credentials.
    await page.getByLabel('Environment').selectOption('SANDBOX');
    await page.getByLabel('Access token').fill(SANDBOX_TOKEN!);
    await page.getByLabel('Location ID').fill(SANDBOX_LOCATION_ID!);
    await page.getByRole('button', { name: /^save$/i }).first().click();
    await expect(page.getByText('Saved.').first()).toBeVisible({ timeout: 10_000 });
    // A masked token placeholder replaces the blank input once genuinely connected.
    await expect(page.getByLabel('Access token')).toHaveAttribute('placeholder', /currently set/i);

    // Step 2: invite a manager — UsersManager's form is always visible (not behind a toggle),
    // with data-testid hooks meant for exactly this.
    const managerUsername = `e2emgr_${suffix}`;
    const userForm = page.getByTestId('user-form');
    await userForm.getByLabel('Username').fill(managerUsername);
    await userForm.getByLabel('Temp password').fill('e2e-test-password-2');
    await userForm.getByLabel('Role').selectOption('MANAGER');
    await page.getByTestId('user-submit').click();
    await expect(page.getByTestId('user-table').getByText(managerUsername)).toBeVisible({ timeout: 10_000 });
  });
});
