import test from 'node:test';
import assert from 'node:assert/strict';
import { loadTs } from './_load.mjs';

const { pageOptionLabel, firstActiveSlug } = await loadTs('../app/lib/marketingPages.ts');

test('inactive page is labelled, active page is not', () => {
  assert.equal(pageOptionLabel({ slug: 'pmu', name: 'AK PMU Landing', active: false }), 'AK PMU Landing (inactive)');
  assert.equal(pageOptionLabel({ slug: 'home', name: 'Home Page', active: true }), 'Home Page');
  assert.equal(pageOptionLabel({ slug: 'x', name: 'Old API row' }), 'Old API row');
});

test('default is the first active page, never an inactive one', () => {
  const pages = [
    { slug: 'home', name: 'Home Page', active: true },
    { slug: 'mani', name: 'Mani Landing', active: false },
  ];
  assert.equal(firstActiveSlug(pages), 'home');
  assert.equal(firstActiveSlug([{ slug: 'mani', name: 'Mani Landing', active: false }]), undefined);
});
