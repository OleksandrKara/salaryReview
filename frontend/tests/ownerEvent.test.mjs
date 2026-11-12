import test from 'node:test';
import assert from 'node:assert/strict';
import { loadTs } from './_load.mjs';

const { parseOwnerChange, ownerChangeSentence } = await loadTs('../app/lib/ownerEvent.ts');

const words = {
  took: '{to} took this lead. Was: {from}',
  assigned: '{by} assigned the lead to {to}. Was: {from}',
  reset: '{by} returned the lead to default: {to}. Was: {from}',
  team: 'the team',
  defaultTag: ' (default)',
};
const ev = (from, to, by, reset = false) => JSON.stringify({ v: 1, from, to, by, reset });

test('owner takes a lead from the Academy default', () => {
  const c = parseOwnerChange(ev({ name: 'Anastasia', kind: 'DEFAULT' }, { name: 'Alex Kara (owner)', kind: 'ASSIGNED' }, 'Alex Kara (owner)'));
  assert.equal(ownerChangeSentence(c, words), 'Alex Kara (owner) took this lead. Was: Anastasia (default)');
});

test('manager assigns the lead to someone else', () => {
  const c = parseOwnerChange(ev({ name: 'Alex Kara (owner)', kind: 'ASSIGNED' }, { name: 'Anastasia', kind: 'ASSIGNED' }, 'Eugenia'));
  assert.equal(ownerChangeSentence(c, words), 'Eugenia assigned the lead to Anastasia. Was: Alex Kara (owner)');
});

test('Anastasia assigns herself and the owner sees it', () => {
  const c = parseOwnerChange(ev({ name: 'Alex Kara (owner)', kind: 'ASSIGNED' }, { name: 'Anastasia', kind: 'ASSIGNED' }, 'Anastasia'));
  assert.equal(ownerChangeSentence(c, words), 'Anastasia took this lead. Was: Alex Kara (owner)');
});

test('reset to default and team lead', () => {
  const c = parseOwnerChange(ev({ name: 'Alex Kara (owner)', kind: 'ASSIGNED' }, { name: null, kind: 'TEAM' }, 'System', true));
  assert.equal(ownerChangeSentence(c, words), 'System returned the lead to default: the team. Was: Alex Kara (owner)');
});

test('legacy events and junk are not parsed', () => {
  assert.equal(parseOwnerChange('Owner (owner)'), null);
  assert.equal(parseOwnerChange('none'), null);
  assert.equal(parseOwnerChange('{bad json'), null);
  assert.equal(parseOwnerChange(null), null);
});
