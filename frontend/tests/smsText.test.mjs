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
  assert.ok(fn.includes('setDraftError(sendFailureMessage(result.reason, result.detail))'));
  assert.ok(fn.includes('catch {') && fn.includes('setDraftError('));
  assert.equal(fn.split("setDraft('')").length - 1, 1, 'draft cleared in exactly one place (success)');
  assert.ok(src.includes('|| sending ||'), 'Send is disabled while a send is in flight');
});

test('segment info: GSM-7 vs UCS-2 and segment counts', async () => {
  const t = await loadTs('../app/admin/messages/smsText.ts');
  assert.deepEqual(
    [t.smsSegmentInfo('Hello').encoding, t.smsSegmentInfo('Hello').segments],
    ['GSM-7', 1],
  );
  assert.equal(t.smsSegmentInfo('a'.repeat(160)).segments, 1);
  assert.equal(t.smsSegmentInfo('a'.repeat(161)).segments, 2);
  assert.equal(t.smsSegmentInfo('a'.repeat(306)).segments, 2);
  assert.equal(t.smsSegmentInfo('a'.repeat(307)).segments, 3);
  const ru = t.smsSegmentInfo('Привет');
  assert.equal(ru.encoding, 'UCS-2');
  assert.equal(t.smsSegmentInfo('я'.repeat(70)).segments, 1);
  assert.equal(t.smsSegmentInfo('я'.repeat(71)).segments, 2);
  assert.equal(t.smsSegmentInfo('Hi 😀').encoding, 'UCS-2');
  // extension chars count double in GSM-7
  assert.equal(t.smsSegmentInfo('{'.repeat(81)).segments, 2);
});

test('length warning: nothing for short plain text, info for long, error naming the limit past 1600', async () => {
  const t = await loadTs('../app/admin/messages/smsText.ts');
  assert.equal(t.smsLengthWarning('Hello there'), null);
  assert.equal(t.smsLengthWarning(''), null);
  const info = t.smsLengthWarning('a'.repeat(400));
  assert.equal(info.level, 'info');
  assert.match(info.text, /3 SMS segments/);
  const emoji = t.smsLengthWarning('Hi 😀');
  assert.match(emoji.text, /UCS-2/);
  const err = t.smsLengthWarning('a'.repeat(1650));
  assert.equal(err.level, 'error');
  assert.match(err.text, /1600/);
  assert.match(err.text, /50 characters/);
  assert.match(err.text, /blocked/);
  assert.equal(t.isOverSmsLimit('a'.repeat(1600)), false);
  assert.equal(t.isOverSmsLimit('a'.repeat(1601)), true);
});

test('failure messages cover the new pre-send reasons and Twilio detail', async () => {
  const t = await loadTs('../app/admin/messages/smsText.ts');
  assert.match(t.sendFailureMessage('empty'), /empty/);
  assert.match(t.sendFailureMessage('invalid_phone'), /not valid/);
  assert.match(t.sendFailureMessage('media_too_large'), /5 MB/);
  assert.match(t.sendFailureMessage('send_failed', 'Recipient has opted out (replied STOP)'), /opted out/);
  assert.match(t.SEND_UNKNOWN_MESSAGE, /Check the conversation/);
});

test('composer blocks over-limit sends in requestSend, sendReply, the Send button and the templates editor', async () => {
  const fs = await import('node:fs');
  const src = fs.readFileSync(new URL('../app/admin/messages/MessagesView.tsx', import.meta.url), 'utf8');
  assert.equal((src.match(/isOverSmsLimit\(/g) ?? []).length >= 3, true);
  assert.ok(src.includes('setSendUnknown(true)'));
  const tpl = fs.readFileSync(new URL('../app/owner/settings/automations/TemplatesPanel.tsx', import.meta.url), 'utf8');
  assert.ok(tpl.includes('isOverSmsLimit(value)'));
});
