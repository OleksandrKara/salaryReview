import test from 'node:test';
import assert from 'node:assert/strict';
import { loadTs } from './_load.mjs';

const r = await loadTs('../app/admin/leads/reminders.ts');
// 2026-11-10 23:30 PT is already 2026-11-11 UTC: dates must follow Pacific time
const NOW = Date.parse('2026-11-11T07:30:00Z');

test('PT date and time, not UTC', () => {
  assert.equal(r.ptYmd(NOW), '2026-11-10');
  assert.equal(r.ptHm(NOW), '23:30');
});

test('default is 2 calendar days and chips are 1/2/3/5/7', () => {
  assert.equal(r.DEFAULT_DAYS, 2);
  assert.deepEqual(r.REMINDER_CHIPS, [1, 2, 3, 5, 7]);
  assert.equal(r.choiceDate({ days: 2, date: '', time: '09:00', timeSet: false }, NOW), '2026-11-12');
});

test('addDays crosses month and DST boundaries by calendar', () => {
  assert.equal(r.addDays('2026-11-29', 2), '2026-12-01');
  assert.equal(r.addDays('2026-11-01', 1), '2026-11-02');
  assert.equal(r.daysBetween('2026-11-10', '2026-11-17'), 7);
});

test('past check', () => {
  const c = (o) => ({ days: null, date: '', time: '09:00', timeSet: false, ...o });
  assert.equal(r.choiceInPast(c({ date: '2026-11-09' }), NOW), true);
  assert.equal(r.choiceInPast(c({ date: '' }), NOW), true);
  assert.equal(r.choiceInPast(c({ date: '2026-11-10', time: '23:00' }), NOW), true);
  assert.equal(r.choiceInPast(c({ date: '2026-11-10', time: '23:45' }), NOW), false);
  assert.equal(r.choiceInPast(c({ days: 1 }), NOW), false);
});

test('request body: chip days or date, time only when set, trimmed note', () => {
  assert.deepEqual(r.choiceBody({ days: 3, date: '', time: '09:00', timeSet: false }, ' '), { days: 3 });
  assert.deepEqual(r.choiceBody({ days: null, date: '2026-11-14', time: '14:30', timeSet: true }, ' call back '), { date: '2026-11-14', time: '14:30', note: 'call back' });
});

test('chip labels pluralize in both languages', () => {
  assert.equal(r.chipLabel(1, 'EN'), '1 day');
  assert.equal(r.chipLabel(5, 'EN'), '5 days');
  assert.equal(r.chipLabel(1, 'RU'), '1 день');
  assert.equal(r.chipLabel(3, 'RU'), '3 дня');
  assert.equal(r.chipLabel(7, 'RU'), '7 дней');
});

test('dueRelative counts calendar days in PT', () => {
  const due = '2026-11-12T17:00:00Z';
  assert.equal(r.dueRelative(due, NOW).n, 2);
  assert.equal(r.dueRelative(due, NOW).overdue, false);
  assert.equal(r.dueRelative('2026-11-09T17:00:00Z', NOW).overdue, true);
});
