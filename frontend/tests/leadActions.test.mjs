import test from 'node:test';
import assert from 'node:assert/strict';
import { loadTs } from './_load.mjs';

const load = loadTs;
const { actionPlan, isOverdue, isLapsed, hasNextStep, lacksNextStep, DAY } = await load('../app/admin/leads/leadActions.ts');
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

test('next step: a future reminder is a next step, none or overdue is not, closed leads never lack one', () => {
  const fut = new Date(NOW + DAY).toISOString();
  assert.equal(hasNextStep(lead({ callbackAt: fut }), NOW), true);
  assert.equal(lacksNextStep(lead({ callbackAt: fut }), NOW), false);
  assert.equal(lacksNextStep(lead({ callbackAt: null }), NOW), true);
  assert.equal(lacksNextStep(lead({ callbackAt: ago(1) }), NOW), true);
  assert.equal(lacksNextStep(lead({ status: 'LOST' }), NOW), false);
});
