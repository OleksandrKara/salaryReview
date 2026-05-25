import { expect, test } from './fixtures';

test.describe('Providers admin', () => {
  // IDs created by the current test; afterEach cleans them up regardless of
  // pass/fail. Reset per test.
  let createdProviderIds: number[] = [];

  test.beforeEach(() => {
    createdProviderIds = [];
  });

  test.afterEach(async ({ api }) => {
    await Promise.all(createdProviderIds.map((id) => api.deleteProvider(id).catch(() => undefined)));
  });

  test('add a provider via /providers form; new row visible in period editor', async ({
    providersPage,
    periodEditor,
    api,
  }) => {
    const suffix = Math.random().toString(36).slice(2, 8);
    const displayName = `UI_${suffix}`;
    const fullName = `UI Test ${suffix}`;

    await providersPage.goto();
    await providersPage.addProvider({
      name: fullName,
      displayName,
      ratePct: 40,
      feePct: 3.5,
    });

    // Resolve id ASAP so afterEach can clean up even if a later assertion throws.
    const all = await api.listProviders({ includeInactive: true });
    const created = all.find((p) => p.displayName === displayName);
    if (created) createdProviderIds.push(created.id);

    // New provider is rendered as an editable <input> on the admin page.
    await expect(providersPage.displayNameInput(displayName)).toBeVisible();

    // ... and as a normal table cell in the period editor.
    await periodEditor.goto(1);
    await expect(periodEditor.page.getByText(displayName)).toBeVisible();
  });
});
