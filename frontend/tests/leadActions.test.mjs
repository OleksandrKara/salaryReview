import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';

const require = createRequire(import.meta.url);
const ts = require('typescript');

async function load(rel) {
  const src = fs.readFileSync(new URL(rel, import.meta.url), 'utf8');
  const out = ts.transpileModule(src, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText;
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'lead-actions-'));
  const file = path.join(dir, 'm.mjs');
  fs.writeFileSync(file, out);
  return import(pathToFileURL(file).href);
}

const { actionPlan, isOverdue, isLapsed, reachedOutDays, DAY } = await load('../app/admin/leads/leadActions.ts');
const NOW = Date.parse('2026-11-07T12:00:00Z');
const lead = (o) => ({ status: 'CONTACTED', callbackAt: null, closeReady: false, phoneNumber: '+16195550000', lastVisitAt: null, ...o });
const ago = (days) => new Date(NOW - days * DAY).toISOString();

test('a due reminder offers Reached out as the main action', () => {
  const p = actionPlan(lead({ callbackAt: ago(1) }), NOW);
  assert.equal(p.primary, 'REACHED');
  assert.equal(p.reachedSecondary, false);
  assert.ok(p.noAnswer && p.spam && p.call && p.text);
});

test('a new lead offers Reached out as the main action', () => {
  assert.equal(actionPlan(lead({ status: 'NEW' }), NOW).primary, 'REACHED');
});

test('a not-yet-due lead still lets the manager log a conversation, but not as the main action', () => {
  const p = actionPlan(lead({ callbackAt: new Date(NOW + DAY).toISOString() }), NOW);
  assert.equal(p.primary, null);
  assert.equal(p.reachedSecondary, true);
});

test('three unanswered attempts put Close first and hide No answer', () => {
  const p = actionPlan(lead({ callbackAt: ago(1), closeReady: true }), NOW);
  assert.equal(p.primary, 'CLOSE');
  assert.equal(p.noAnswer, false);
  assert.equal(p.reachedSecondary, true);
});

test('closed leads and leads without a phone get no wrong buttons', () => {
  for (const status of ['BOOKED', 'LOST', 'SPAM', 'OUTDATED']) {
    const p = actionPlan(lead({ status, callbackAt: ago(1) }), NOW);
    assert.deepEqual([p.primary, p.reachedSecondary, p.noAnswer, p.spam, p.call, p.text], [null, false, false, false, false, false]);
  }
  const noPhone = actionPlan(lead({ phoneNumber: null, callbackAt: ago(1) }), NOW);
  assert.equal(noPhone.call || noPhone.text, false);
  assert.equal(noPhone.primary, 'REACHED');
});

test('overdue and lapsed rules', () => {
  assert.equal(isOverdue(lead({ callbackAt: ago(0.01) }), NOW), true);
  assert.equal(isOverdue(lead({ callbackAt: new Date(NOW + 1000).toISOString() }), NOW), false);
  assert.equal(isOverdue(lead({ status: 'LOST', callbackAt: ago(1) }), NOW), false);
  assert.equal(isLapsed(lead({ lastVisitAt: ago(31) }), NOW), true);
  assert.equal(isLapsed(lead({ lastVisitAt: ago(29) }), NOW), false);
});

test('next check-in matches the backend: 30 days for lapsed clients, 7 for others', () => {
  assert.equal(reachedOutDays(lead({ lastVisitAt: ago(45) }), NOW), 30);
  assert.equal(reachedOutDays(lead({ lastVisitAt: ago(10) }), NOW), 7);
  assert.equal(reachedOutDays(lead({}), NOW), 7);
});
