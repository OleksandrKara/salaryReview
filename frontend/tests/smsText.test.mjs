import test from 'node:test';
import assert from 'node:assert/strict';
import { loadTs } from './_load.mjs';

const m = await loadTs('../app/admin/messages/smsText.ts');

test('parseBold: single and double asterisk pairs become bold', () => {
  assert.deepEqual(m.parseBold('Hi *Anna* and **Bob**!'), [
    { text: 'Hi ', bold: false }, { text: 'Anna', bold: true },
    { text: ' and ', bold: false }, { text: 'Bob', bold: true }, { text: '!', bold: false },
  ]);
});

test('parseBold: lone asterisks, bullets, math and cross-line pairs stay literal', () => {
  for (const s of ['5 * 3 * 2', '* item\n* item two', 'a*b', 'x *a\nb* y', 'price* $50']) {
    assert.equal(m.hasBoldMarkers(s), false, s);
    assert.equal(m.parseBold(s).map((p) => p.text).join(''), s);
  }
});

test('parseBold: HTML-looking text is returned as plain text, never interpreted', () => {
  const parts = m.parseBold('<b>x</b> *y*');
  assert.equal(parts[0].text, '<b>x</b> ');
});

test('stripBoldMarkers removes only real pairs', () => {
  assert.equal(m.stripBoldMarkers('**Note**: 5 * 3 and *ok*'), 'Note: 5 * 3 and ok');
});

test('smsLengthState thresholds', () => {
  assert.equal(m.smsLengthState('a'.repeat(100)), 'ok');
  assert.equal(m.smsLengthState('a'.repeat(700)), 'long');
  assert.equal(m.smsLengthState('a'.repeat(1601)), 'too_long');
});

import fs from 'node:fs';

test('failure messages say nothing was delivered and mention the reason', () => {
  assert.match(m.sendFailureMessage('too_long'), /1600/);
  assert.match(m.sendFailureMessage('duplicate_recent'), /just sent/);
  assert.match(m.sendFailureMessage('send_failed'), /Nothing was delivered/);
  assert.match(m.sendFailureMessage(null), /still in the box/);
});

test('composer: draft is cleared only on a successful send, failures show an error, no silent path', () => {
  const src = fs.readFileSync(new URL('../app/admin/messages/MessagesView.tsx', import.meta.url), 'utf8');
  const fn = src.slice(src.indexOf('async function sendReply()'), src.indexOf('function insertEmojiIntoDraft'));
  const okBranch = fn.slice(fn.indexOf('if (result.sent) {'), fn.indexOf('} else {'));
  assert.ok(okBranch.includes("setDraft('')") && okBranch.includes('focus()'));
  assert.ok(fn.includes('setDraftError(sendFailureMessage(result.reason))'));
  assert.ok(fn.includes('catch {') && fn.includes('setDraftError('));
  assert.equal(fn.split("setDraft('')").length - 1, 1, 'draft cleared in exactly one place (success)');
  assert.ok(src.includes('|| sending ||'), 'Send is disabled while a send is in flight');
});
