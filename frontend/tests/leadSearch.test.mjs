import test from 'node:test';
import assert from 'node:assert/strict';
import { loadTs } from './_load.mjs';

const S = await loadTs('../app/admin/leads/leadSearch.ts');
const lead = (o) => ({ name: 'Irina O\'Reilley', phoneNumber: '+16194540318', email: 'iriegirl_23@yahoo.com', source: 'Google Ads', lastNote: 'texted, voice', ...o });
const hit = (q, l = lead({})) => S.leadMatches(l, S.parseSearch(q));

test('phone is found however it is typed', () => {
  for (const q of ['6194540318', '619 454 0318', '(619) 454-0318', '619-454-0318', '619.454.0318', '+1 619 454 0318', '+1 (619) 454-0318', '1 619 454 0318', '16194540318', '454-0318', '(619', '+1 619', '0318']) {
    assert.equal(hit(q), true, q);
  }
});

test('country code is ignored on either side', () => {
  const noCode = lead({ phoneNumber: '6196274005' });
  assert.equal(hit('+1 (619) 627-4005', noCode), true);
  assert.equal(hit('16196274005', noCode), true);
  assert.equal(hit('619 627 4005', noCode), true);
  const formatted = lead({ phoneNumber: '(619) 627-4005' });
  assert.equal(hit('+16196274005', formatted), true);
});

test('wrong digits do not match', () => {
  assert.equal(hit('619 454 0319'), false);
  assert.equal(hit('+1 (858) 454-0318'), false);
});

test('names and emails are case and accent insensitive, several words in any order', () => {
  assert.equal(hit('IRINA'), true);
  assert.equal(hit('reilley irina'), true);
  assert.equal(hit('iriegirl'), true);
  assert.equal(hit('yahoo.com'), true);
  assert.equal(hit('jose', lead({ name: 'José Álvarez' })), true);
  assert.equal(hit('alvarez jose', lead({ name: 'José Álvarez' })), true);
  assert.equal(hit('maria'), false);
});

test('mixed text and digits work together', () => {
  assert.equal(hit('irina 619'), true);
  assert.equal(hit('irina (619) 454'), true);
  assert.equal(hit('irina 858'), false);
});

test('digits inside an email still match', () => {
  assert.equal(hit('23', lead({ phoneNumber: null })), true);
});

test('empty query matches everything and a missing phone never crashes', () => {
  assert.equal(hit('   '), true);
  assert.equal(hit('619', lead({ phoneNumber: null })), false);
  assert.equal(S.parseSearch('+1 (619) 454-0318').digits, '6194540318');
  assert.equal(S.parseSearch('irina').phoneLike, false);
});
