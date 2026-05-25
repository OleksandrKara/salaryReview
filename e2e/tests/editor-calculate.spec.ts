import { expect, test } from './fixtures';

test.describe('Editor + Calculate flow', () => {
  test('edit Anna row and Calculate renders the WhatsApp-ready message', async ({ periodEditor }) => {
    const ANNA_ID = 1;

    // Seeded period 1 (1-15 May 2026), seeded provider 1 (Anna, rate 0.45).
    // V2 seeds her real numbers — but re-enter them to make this test
    // self-contained even if a previous test mutated the row.
    await periodEditor.goto(1);

    await expect(periodEditor.heading).toContainText('1-15 May 2026');
    await expect(periodEditor.entryProvider(ANNA_ID)).toContainText('Anna');

    await periodEditor.fillEntry(ANNA_ID, {
      procedures: 5,
      card: 473,
      cash: 291,
      tips: '74.30',
      adj: 0,
      ratePct: '',   // clear per-period override -> fall back to provider default 45%
    });

    await periodEditor.clickCalculate();

    const annaMsg = periodEditor.settlementMessage(ANNA_ID);
    await expect(annaMsg).toContainText('#salary 1-15 May 2026');
    await expect(annaMsg).toContainText('Zelle AK to Anna: $284.55');
    await expect(annaMsg).toContainText('Cash from Anna to AK: $160.05');
  });
});
