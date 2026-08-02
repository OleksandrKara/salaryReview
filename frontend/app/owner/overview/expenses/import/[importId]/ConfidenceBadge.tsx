import type { BankTransaction } from '../../../../../lib/types';

/** Exactly three visual states, never a raw percentage (openspec design.md §10/D5): a
 * percentage invites second-guessing a well-calibrated auto-match, while a badge tells the owner
 * exactly what to do with the row (nothing / decide / decide). */
export default function ConfidenceBadge({ txn }: { txn: BankTransaction }) {
  if (txn.status === 'AUTO_MATCHED' || txn.status === 'REVIEWED') {
    return (
      <span className="inline-flex items-center gap-1 rounded-full bg-emerald-50 px-2 py-0.5 text-[10px] font-medium text-emerald-700 ring-1 ring-inset ring-emerald-200">
        ✅ {txn.status === 'REVIEWED' ? 'Reviewed' : 'Categorized'}
      </span>
    );
  }
  if (txn.status === 'EXCLUDED') {
    return (
      <span className="inline-flex items-center gap-1 rounded-full bg-zinc-100 px-2 py-0.5 text-[10px] font-medium text-zinc-500 ring-1 ring-inset ring-zinc-200">
        Excluded
      </span>
    );
  }
  if (txn.status === 'DUPLICATE') {
    return (
      <span className="inline-flex items-center gap-1 rounded-full bg-zinc-100 px-2 py-0.5 text-[10px] font-medium text-zinc-400 ring-1 ring-inset ring-zinc-200">
        Duplicate
      </span>
    );
  }
  if (txn.confidence !== null) {
    return (
      <span className="inline-flex items-center gap-1 rounded-full bg-amber-50 px-2 py-0.5 text-[10px] font-medium text-amber-700 ring-1 ring-inset ring-amber-200">
        ⚠ Unsure
      </span>
    );
  }
  return (
    <span className="inline-flex items-center gap-1 rounded-full bg-red-50 px-2 py-0.5 text-[10px] font-medium text-red-600 ring-1 ring-inset ring-red-200">
      ❌ Unknown
    </span>
  );
}
