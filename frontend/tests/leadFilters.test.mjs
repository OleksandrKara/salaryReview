import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';

const ts = createRequire(import.meta.url)('typescript');
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'lf-'));
for (const name of ['reminders', 'leadFilters']) {
  const src = fs.readFileSync(new URL(`../app/admin/leads/${name}.ts`, import.meta.url), 'utf8').replace("'./reminders'", "'./reminders.mjs'");
  fs.writeFileSync(path.join(dir, `${name}.mjs`), ts.transpileModule(src, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText);
}
const F = await import(pathToFileURL(path.join(dir, 'leadFilters.mjs')).href);
const q = (s) => new URLSearchParams(s);
// 2026-11-17 10:00 in Los Angeles (PST, UTC-8)
const NOW = Date.parse('2026-11-17T18:00:00Z');

test('presets use Los Angeles days, not UTC days', () => {
  const lateNight = Date.parse('2026-11-18T05:30:00Z'); // 21:30 on the 17th in LA
  assert.deepEqual(F.presetRange('today', lateNight), { from: '2026-11-17', to: '2026-11-17' });
  assert.deepEqual(F.presetRange('yesterday', lateNight), { from: '2026-11-16', to: '2026-11-16' });
  const earlyUtc = Date.parse('2026-11-17T07:30:00Z'); // 23:30 on the 16th in LA
  assert.deepEqual(F.presetRange('today', earlyUtc), { from: '2026-11-16', to: '2026-11-16' });
});

test('presets: last 7, last 30, this month, last month', () => {
  assert.deepEqual(F.presetRange('last7', NOW), { from: '2026-11-11', to: '2026-11-17' });
  assert.deepEqual(F.presetRange('last30', NOW), { from: '2026-10-19', to: '2026-11-17' });
  assert.deepEqual(F.presetRange('thisMonth', NOW), { from: '2026-11-01', to: '2026-11-17' });
  assert.deepEqual(F.presetRange('lastMonth', NOW), { from: '2026-10-01', to: '2026-10-31' });
  const jan = Date.parse('2027-01-10T20:00:00Z');
  assert.deepEqual(F.presetRange('lastMonth', jan), { from: '2026-12-01', to: '2026-12-31' });
  const mar = Date.parse('2027-03-05T20:00:00Z');
  assert.deepEqual(F.presetRange('lastMonth', mar), { from: '2027-02-01', to: '2027-02-28' });
});

test('URL round trip keeps sources, statuses, presets, custom ranges and sort', () => {
  const f = F.parseFilters(q('src=Google%20Ads&src=Instagram%20(organic)&st=new&st=bogus&created=last7&activity=2026-11-01_2026-11-10&sort=activity&dir=asc'), NOW);
  assert.deepEqual(f.sources, ['Google Ads', 'Instagram (organic)']);
  assert.deepEqual(f.statuses, ['NEW']);
  assert.equal(f.created.preset, 'last7');
  assert.equal(f.created.from, '2026-11-11');
  assert.deepEqual(f.activity, { preset: 'custom', from: '2026-11-01', to: '2026-11-10' });
  assert.equal(f.sort, 'activity');
  assert.equal(f.asc, true);
  const url = F.filtersToUrlParams(f);
  assert.equal(url.get('created'), 'last7');
  assert.equal(url.get('activity'), '2026-11-01_2026-11-10');
  assert.deepEqual(F.parseFilters(url, NOW), f);
});

test('API params carry explicit dates and repeat multi-select values', () => {
  const f = F.parseFilters(q('src=A&src=B&st=LOST&created=today&sort=created'), NOW);
  const api = F.filtersToApiParams(f);
  assert.deepEqual(api.getAll('source'), ['A', 'B']);
  assert.deepEqual(api.getAll('status'), ['LOST']);
  assert.equal(api.get('createdFrom'), '2026-11-17');
  assert.equal(api.get('createdTo'), '2026-11-17');
  assert.equal(api.get('sort'), 'created');
  assert.equal(api.get('dir'), 'desc');
  assert.equal(F.filtersToApiParams(f, 1000).get('offset'), '1000');
});

test('empty and garbage params are the default state', () => {
  const f = F.parseFilters(q('created=nonsense&activity=custom&sort=name&tab=ALL&business=2'), NOW);
  assert.equal(F.isDefault(f), true);
  assert.equal(F.activeCount(f), 0);
  assert.equal(F.filtersToUrlParams(f).toString(), '');
});

test('half-open custom ranges are kept', () => {
  const f = F.parseFilters(q('created=_2026-11-10'), NOW);
  assert.deepEqual(f.created, { preset: 'custom', from: '', to: '2026-11-10' });
  assert.equal(F.filtersToApiParams(f).get('createdFrom'), null);
});
