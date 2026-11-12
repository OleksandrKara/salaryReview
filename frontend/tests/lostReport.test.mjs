import test from 'node:test';
import assert from 'node:assert/strict';
import { loadTs } from './_load.mjs';

const { parseLostFilters, lostHref, toggleSource, filterLeads, sourceSummaries, reasonCounts } = await loadTs('../app/lib/lostReport.ts');

const L = (id, source, reason) => ({ id, name: 'N', phoneLast4: '1111', channel: 'Form', source, reason, comment: null, lostAt: '2026-11-01T00:00:00Z' });
const leads = [L(1, 'Yelp', 'PRICE'), L(2, 'Yelp', 'PRICE'), L(3, 'Yelp', 'TIMING'), L(4, 'Google Ads', 'PRICE'), L(5, null, 'OTHER')];

test('filters parse from the URL with safe defaults', () => {
  assert.deepEqual(parseLostFilters({}), { days: 90, sources: [], reason: null });
  assert.deepEqual(parseLostFilters({ days: '30', source: ['Yelp', 'Yelp', 'Google Ads'], reason: 'PRICE' }), { days: 30, sources: ['Yelp', 'Google Ads'], reason: 'PRICE' });
  assert.deepEqual(parseLostFilters({ days: '7', source: 'Yelp', reason: 'drop table' }), { days: 90, sources: ['Yelp'], reason: null });
});

test('href round-trips and omits defaults', () => {
  assert.equal(lostHref({ days: 90, sources: [], reason: null }), '/admin/leads/lost-report');
  const f = { days: 180, sources: ['Google Ads', 'Yelp'], reason: 'PRICE' };
  assert.deepEqual(parseLostFilters(Object.fromEntries([['days', '180'], ['reason', 'PRICE']])).days, 180);
  assert.equal(lostHref(f), '/admin/leads/lost-report?days=180&source=Google+Ads&source=Yelp&reason=PRICE');
});

test('source toggles on and off', () => {
  const f = { days: 90, sources: [], reason: null };
  assert.deepEqual(toggleSource(f, 'Yelp').sources, ['Yelp']);
  assert.deepEqual(toggleSource(toggleSource(f, 'Yelp'), 'Yelp').sources, []);
});

test('leads filter by source and reason, missing source is its own bucket', () => {
  assert.equal(filterLeads(leads, { days: 90, sources: ['Yelp'], reason: null }).length, 3);
  assert.equal(filterLeads(leads, { days: 90, sources: ['Yelp'], reason: 'PRICE' }).length, 2);
  assert.deepEqual(filterLeads(leads, { days: 90, sources: [''], reason: null }).map((l) => l.id), [5]);
});

test('per-source summary has count, share and top reasons; reason filter narrows it', () => {
  const s = sourceSummaries(leads, { days: 90, sources: [], reason: null });
  assert.equal(s[0].source, 'Yelp');
  assert.equal(s[0].count, 3);
  assert.equal(Math.round(s[0].share * 100), 60);
  assert.deepEqual(s[0].topReasons, [{ reason: 'PRICE', count: 2 }, { reason: 'TIMING', count: 1 }]);
  const priceOnly = sourceSummaries(leads, { days: 90, sources: [], reason: 'PRICE' });
  assert.deepEqual(priceOnly.map((x) => [x.source, x.count]), [['Yelp', 2], ['Google Ads', 1]]);
  assert.deepEqual(reasonCounts(leads)[0], { reason: 'PRICE', count: 3 });
});
