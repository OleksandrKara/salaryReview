import { expect, test } from './fixtures';

test.describe('Home page', () => {
  test('lists pay periods (seeded May 2026 H1 visible)', async ({ homePage }) => {
    await homePage.goto();

    await expect(homePage.title).toHaveText('Salary Review');
    await expect(homePage.periodList).toBeVisible();
    // Seeded period from V2 migration. id=1.
    await expect(homePage.periodLink(1)).toContainText('1-15 May 2026');
  });
});
