import type { LeadStatus } from '../../lib/types';

export function statusLabel(s: LeadStatus): string {
  return { NEW: 'New', CONTACTED: 'Contacted', BOOKED: 'Booked', LOST: 'Lost', SPAM: 'Spam', OUTDATED: 'Archived' }[s];
}

export function statusTone(s: LeadStatus): string {
  switch (s) {
    case 'BOOKED': return 'bg-emerald-50 text-emerald-800';
    case 'LOST': case 'SPAM': return 'bg-zinc-100 text-zinc-600';
    case 'NEW': return 'bg-blue-50 text-blue-800';
    default: return 'bg-amber-50 text-amber-800';
  }
}

/** Where the lead stands after its consultation, from the CRM's consult state (it clears once a procedure is booked). */
export function consultationLabel(state: string | null): string | null {
  switch (state) {
    case 'UPCOMING': return 'Consultation scheduled';
    case 'DONE': return 'Consultation done, no procedure booked yet';
    case 'NO_SHOW': return 'Consultation no-show';
    default: return null;
  }
}
