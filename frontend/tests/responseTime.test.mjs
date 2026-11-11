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
