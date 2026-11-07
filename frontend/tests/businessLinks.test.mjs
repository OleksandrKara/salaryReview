import test from 'node:test';
import assert from 'node:assert/strict';
import { loadTs } from './_load.mjs';

const { parseBusinessParam, safeAdminPath, enterBusinessUrl, needsSwitch, nextBusinessToTry, parseTried } = await loadTs('../app/lib/businessLinks.ts');

test('business param parsing', () => {
  assert.equal(parseBusinessParam('2'), 2);
  assert.equal(parseBusinessParam(['1', '2']), 1);
  for (const bad of [undefined, '', '0', '-1', 'abc', '1.5']) assert.equal(parseBusinessParam(bad), null);
});

test('switch is needed only when the link names a different business', () => {
  assert.equal(needsSwitch(2, 1), true);
  assert.equal(needsSwitch(2, 2), false);
  assert.equal(needsSwitch(null, 1), false);
});

test('next path must stay inside /admin/', () => {
  assert.equal(safeAdminPath('/admin/leads/5?stop=academy'), '/admin/leads/5?stop=academy');
  for (const bad of ['https://evil.com', '//evil.com', '/login', '/admin/../x\\y', '/admin/a\r\nSet-Cookie:x', '', null, undefined]) {
    assert.equal(safeAdminPath(bad), null, String(bad));
  }
});

test('enter URL encodes the destination', () => {
  const u = new URL(enterBusinessUrl(2, '/admin/leads/5?stop=academy&tried=1,2'), 'http://x');
  assert.equal(u.pathname, '/api/business/enter');
  assert.equal(u.searchParams.get('business'), '2');
  assert.equal(u.searchParams.get('next'), '/admin/leads/5?stop=academy&tried=1,2');
});

test('probing other businesses visits each once and then stops', () => {
  assert.equal(nextBusinessToTry([1, 2], [1]), 2);
  assert.equal(nextBusinessToTry([1, 2], [1, 2]), null);
  assert.equal(nextBusinessToTry([1], [1]), null);
  assert.deepEqual(parseTried('1,2,x,-3'), [1, 2]);
  assert.deepEqual(parseTried(undefined), []);
});
