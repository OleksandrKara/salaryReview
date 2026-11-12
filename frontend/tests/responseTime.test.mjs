import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { loadTs } from './_load.mjs';

const m = await loadTs('../app/lib/responseTime.ts');

test('formatWait renders seconds, minutes and hours in both languages', () => {
  assert.equal(m.formatWait(null, 'EN'), '-');
  assert.equal(m.formatWait(45, 'EN'), '45 s');
  assert.equal(m.formatWait(200, 'EN'), '3 min 20 s');
  assert.equal(m.formatWait(660, 'EN'), '11 min');
  assert.equal(m.formatWait(7500, 'EN'), '2 h 05 min');
  assert.equal(m.formatWait(200, 'RU'), '3 мин 20 с');
});

test('speedTone bands', () => {
  assert.equal(m.speedTone(null), 'none');
  assert.equal(m.speedTone(300), 'good');
  assert.equal(m.speedTone(301), 'ok');
  assert.equal(m.speedTone(901), 'slow');
});

test('rankManagers: fastest median first, managers without replies last', () => {
  const rows = [
    { n: 'a', replies: 0, medianSeconds: null },
    { n: 'b', replies: 3, medianSeconds: 600 },
    { n: 'c', replies: 5, medianSeconds: 90 },
  ];
  assert.deepEqual(m.rankManagers(rows).map((r) => r.n), ['c', 'b', 'a']);
});

test('a manager lands on the CRM after sign in and is not bounced from it', () => {
  const proxy = fs.readFileSync(new URL('../proxy.ts', import.meta.url), 'utf8');
  assert.match(proxy, /const MANAGER_HOME = '\/admin\/leads'/);
  const landing = fs.readFileSync(new URL('../app/Landing.tsx', import.meta.url), 'utf8');
  assert.match(landing, /role === 'MANAGER' \? '\/admin\/leads'/);
  const root = fs.readFileSync(new URL('../app/page.tsx', import.meta.url), 'utf8');
  assert.match(root, /me\.role === 'MANAGER'[\s\S]{0,40}target = '\/admin\/leads'/);
});

test('the metrics proxy routes exist and the response-times page is staff only', () => {
  assert.ok(fs.existsSync(new URL('../app/api/metrics/shift/route.ts', import.meta.url)));
  assert.ok(fs.existsSync(new URL('../app/api/metrics/response/route.ts', import.meta.url)));
  const proxy = fs.readFileSync(new URL('../proxy.ts', import.meta.url), 'utf8');
  assert.match(proxy, /STAFF_ONLY = \[[^\]]*'\/admin\/response-times'/);
});

test('shiftState separates never clocked in, quiet shift and active shift', () => {
  assert.equal(m.shiftState({ shiftStart: null, replies: 0, leadsProcessed: 0 }), 'none');
  assert.equal(m.shiftState({ shiftStart: '2026-11-11T16:00:00Z', replies: 0, leadsProcessed: 0 }), 'quiet');
  assert.equal(m.shiftState({ shiftStart: '2026-11-11T16:00:00Z', replies: 0, leadsProcessed: 2 }), 'active');
  assert.equal(m.shiftState({ shiftStart: '2026-11-11T16:00:00Z', replies: 3, leadsProcessed: 0 }), 'active');
});

test('chatHref deep-links into the messages thread and escapes the plus sign', () => {
  assert.equal(m.chatHref('+14155550123'), '/admin/messages?phone=%2B14155550123');
  assert.equal(m.SLOW_WAIT_SECONDS, 900);
});

test('leadHref and sinceLabel', () => {
  assert.equal(m.leadHref(42), '/admin/leads/42');
  assert.match(m.sinceLabel('2026-11-07T02:45:00Z', 'EN'), /Nov 6/);
});

test('inBusiness only routes through the switcher for another business', async () => {
  const b = await loadTs('../app/lib/businessLinks.ts');
  assert.equal(b.inBusiness('/admin/leads/42', 1, 1), '/admin/leads/42');
  assert.equal(b.inBusiness('/admin/leads/42', null, 1), '/admin/leads/42');
  assert.equal(b.inBusiness('/admin/leads/42', 2, 1), '/api/business/enter?business=2&next=%2Fadmin%2Fleads%2F42');
});

test('waiting rows offer chat and lead links, and flag chats that are not leads', () => {
  const src = fs.readFileSync(new URL('../app/components/WaitingRows.tsx', import.meta.url), 'utf8');
  assert.match(src, /chatHref\(w\.phone\)/);
  assert.match(src, /leadHref\(w\.leadId\)/);
  assert.match(src, /rtNoLead/);
  assert.match(src, /min-h-11/);
  assert.match(src, /SLOW_WAIT_SECONDS/);
  for (const f of ['../app/admin/leads/ShiftSummary.tsx', '../app/admin/response-times/ResponseTimesView.tsx']) {
    assert.match(fs.readFileSync(new URL(f, import.meta.url), 'utf8'), /WaitingRows/);
  }
});
