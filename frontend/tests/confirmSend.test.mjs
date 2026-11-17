import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { loadTs } from './_load.mjs';

const c = await loadTs('../app/admin/messages/confirmSend.ts');

test('phone is shown as last 4 digits only', () => {
  assert.equal(c.phoneLast4('+1 (619) 555-0142'), '0142');
  assert.equal(c.maskedPhone('+16195550142'), '•••• 0142');
  assert.equal(c.maskedPhone(''), '');
  assert.ok(!c.maskedPhone('+16195550142').includes('619'));
});

test('confirm is ignored right after opening (double-tap guard)', () => {
  assert.equal(c.confirmArmed(1000, 1000 + c.CONFIRM_ARM_MS - 1), false);
  assert.equal(c.confirmArmed(1000, 1000 + c.CONFIRM_ARM_MS), true);
});

test('channel is MMS with photos, SMS otherwise', () => {
  assert.equal(c.channelFor(0), 'SMS');
  assert.equal(c.channelFor(2), 'MMS');
});

test('a manual send cannot fire without the confirm dialog', () => {
  const src = fs.readFileSync(new URL('../app/admin/messages/MessagesView.tsx', import.meta.url), 'utf8');
  const form = src.slice(src.indexOf('data-testid="thread-composer"'), src.indexOf('data-testid="thread-composer"') + 300);
  assert.ok(form.includes('requestSend()'));
  assert.ok(!form.includes('sendReply()'));
  const calls = src.match(/(?<!function )sendReply\(\)/g) ?? [];
  assert.equal(calls.length, 1, 'sendReply() is called only from the dialog onConfirm');
  assert.ok(src.includes('onConfirm={() => { void sendReply(); }}'));
  assert.equal((src.match(/api\.sendSmsReply(WithMedia)?\(/g) ?? []).length, 2);
});

test('dialog: Enter never confirms, Escape and backdrop cancel, copy exists in EN and RU', () => {
  const d = fs.readFileSync(new URL('../app/admin/messages/ConfirmSendDialog.tsx', import.meta.url), 'utf8');
  assert.ok(d.includes("e.key === 'Enter' && (e.target as HTMLElement).tagName !== 'BUTTON'"));
  assert.ok(d.includes("e.key === 'Escape'"));
  assert.ok(d.includes('data-sheet-handle'));
  const i = fs.readFileSync(new URL('../app/lib/i18n.ts', import.meta.url), 'utf8');
  assert.match(i, /confirmSendConfirm: \{ EN: 'Confirm', RU: 'Подтвердить' \}/);
  assert.match(i, /confirmSendCancel: \{ EN: 'Cancel', RU: 'Отменить' \}/);
});
