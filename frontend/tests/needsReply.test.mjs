import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { loadTs } from './_load.mjs';

const m = await loadTs('../app/lib/responseTime.ts');
const i18n = fs.readFileSync(new URL('../app/lib/i18n.ts', import.meta.url), 'utf8');

const item = { phone: '+13105558008', phoneLast4: '8008', since: '2026-11-10T10:00:00Z', waitSeconds: 4000, ask: true, name: null, leadId: null, leadStatus: null, preview: 'Can I come at 3?', automationKey: 'reminder_24h', automationAt: '2026-11-10T09:00:00Z' };

test('needsReplyRows maps the queue and keeps no-lead rows without an id', () => {
  const [r] = m.needsReplyRows({ items: [item, { ...item, phone: '+1', leadId: 25558, leadStatus: 'OUTDATED', ask: false }] });
  assert.equal(r.key, '+13105558008');
  assert.equal(r.seconds, 4000);
  assert.equal(r.leadId, null);
  assert.equal(r.ask, true);
  assert.equal(r.lastAutoKey, 'reminder_24h');
  assert.equal(m.needsReplyRows({ items: [item, { ...item, leadId: 25558 }] })[1].leadId, 25558);
  assert.equal(m.needsReplyRows({ items: [] }).length, 0);
});

test('the needs-reply strings exist in both languages and use no em-dash', () => {
  for (const k of ['nrTab', 'nrEmpty', 'nrAsk', 'nrStatement', 'nrAutoNote', 'nrCreateLead', 'nrCreating', 'nrCreateFailed', 'nrHint', 'rtAutomationFirst', 'rtAutomationFirstTeam']) {
    const line = i18n.split('\n').find((l) => l.trim().startsWith(`${k}:`));
    assert.ok(line, k);
    assert.match(line, /EN: '/);
    assert.match(line, /RU: '/);
    assert.ok(!line.includes('\u2014'), k);
  }
});

test('the queue tab and the menu badge are wired', () => {
  const lm = fs.readFileSync(new URL('../app/admin/leads/LeadsManager.tsx', import.meta.url), 'utf8');
  const menu = fs.readFileSync(new URL('../app/components/AdminMenu.tsx', import.meta.url), 'utf8');
  assert.match(lm, /tab-needs-reply/);
  assert.match(lm, /\/api\/metrics\/needs-reply/);
  assert.match(menu, /\/api\/metrics\/needs-reply/);
  assert.match(menu, /crm-menu-badge/);
});
