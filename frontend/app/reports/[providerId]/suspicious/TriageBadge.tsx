'use client';

import type { TriageClassification, TriageResult } from '../../../lib/types';

const LABEL: Record<TriageClassification, string> = {
  LIKELY_LEGIT: 'Likely legit',
  NEEDS_REVIEW: 'Needs review',
  LIKELY_FRAUD: 'Likely fraud',
};

/** Per-class Tailwind classes. Kept inline-literal so Tailwind's JIT can statically pick them up. */
const RING: Record<TriageClassification, string> = {
  LIKELY_LEGIT: 'bg-emerald-50 text-emerald-700 ring-emerald-200',
  NEEDS_REVIEW: 'bg-amber-50 text-amber-700 ring-amber-200',
  LIKELY_FRAUD: 'bg-red-50 text-red-700 ring-red-200',
};

/** Small dot color used to make the chip readable at a glance even when the text is muted. */
const DOT: Record<TriageClassification, string> = {
  LIKELY_LEGIT: 'bg-emerald-500',
  NEEDS_REVIEW: 'bg-amber-500',
  LIKELY_FRAUD: 'bg-red-500',
};

/**
 * Tiny classification chip rendered on the row header so the owner can scan a list of triaged
 * bookings and immediately see green/yellow/red without expanding each one. Visible only when a
 * triage exists; renders nothing for un-triaged rows.
 *
 * <p>The full TriagePanel still shows its own larger version with the explanation + draft message
 * + feedback. This is the compact summary view.
 */
export default function TriageBadge({ triage }: { triage: TriageResult | null }) {
  if (!triage) return null;
  const c = triage.classification;
  const pct = Math.round((triage.confidence ?? 0) * 100);
  return (
    <span
      data-testid={`triage-badge-${c}`}
      title={`AI classification: ${LABEL[c]} (${pct}% confidence)`}
      className={`inline-flex shrink-0 items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-medium ring-1 ring-inset ${RING[c]}`}
    >
      <span className={`h-1.5 w-1.5 rounded-full ${DOT[c]}`} aria-hidden="true" />
      {LABEL[c]}
      <span className="tabular-nums opacity-75">· {pct}%</span>
    </span>
  );
}
