import { expect, test } from './fixtures';

test.describe('Pay period lifecycle', () => {
  test('create new period via form and land on its editor', async ({ page, homePage, periodEditor, api }) => {
    // Pick a year/month/half unlikely to clash with seeded or fixture data.
    const year = 2099;
    const month = 3;

    await homePage.goto();
    await homePage.createPeriod(year, month, 'SECOND');

    // Form pushes us to /periods/{id}; URL pattern + heading confirm it.
    await page.waitForURL(/\/periods\/\d+$/);
    await expect(periodEditor.heading).toContainText(`16-31 March ${year}`);

    // Clean up via API — read the id back from the URL
    const match = page.url().match(/\/periods\/(\d+)$/);
    if (match) await api.deletePeriod(Number(match[1]));
  });

  test('delete period via home row ✕ with confirmation removes it from the list', async ({ homePage, freshPeriod }) => {
    await homePage.goto();

    const row = homePage.periodRow(freshPeriod.id);
    await expect(row).toBeVisible();

    await homePage.deletePeriod(freshPeriod.id);

    await expect(row).toBeHidden();
  });
});
