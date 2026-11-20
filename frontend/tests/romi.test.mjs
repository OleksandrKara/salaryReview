import test from 'node:test';
import assert from 'node:assert/strict';
import { loadTs } from './_load.mjs';

const R = await loadTs('../app/owner/marketing/google-ppc/romi.ts');
const m = { materialPerProcedure: 50, masterPct: 45, squarePct: 3 };

test('profit takes the master share, the card fee and materials off revenue', () => {
  // 10 procedures, $6,000 revenue: 6000 * 0.52 = 3120, minus 10 * 50 = 2620
  assert.equal(R.profitAfterCosts(6000, 10, m), 2620);
});

test('romi is profit after spend over spend', () => {
  // (2620 - 1000) / 1000 = 162%
  assert.equal(Math.round(R.romiPercent(6000, 1000, 10, m)), 162);
});

test('romi is empty without spend and negative when ads cost more than profit', () => {
  assert.equal(R.romiPercent(500, 0, 1, m), null);
  assert.ok(R.romiPercent(500, 1000, 1, m) < 0);
});
