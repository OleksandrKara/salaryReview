import test from 'node:test';
import assert from 'node:assert/strict';
import { loadTs } from './_load.mjs';

const A = await loadTs('../app/owner/marketing/adLeads.ts');

test('consultation state reads as the next step, and is empty once a procedure is booked', () => {
  assert.equal(A.consultationLabel('UPCOMING'), 'Consultation scheduled');
  assert.equal(A.consultationLabel('DONE'), 'Consultation done, no procedure booked yet');
  assert.equal(A.consultationLabel('NO_SHOW'), 'Consultation no-show');
  assert.equal(A.consultationLabel(null), null);
});

test('every lead status has a label and a tone', () => {
  for (const s of ['NEW', 'CONTACTED', 'BOOKED', 'LOST', 'SPAM', 'OUTDATED']) {
    assert.ok(A.statusLabel(s));
    assert.ok(A.statusTone(s));
  }
});
