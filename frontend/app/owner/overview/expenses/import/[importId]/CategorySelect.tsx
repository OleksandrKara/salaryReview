import type { ExcludeReason, ExpenseCategory } from '../../../../../lib/types';

export const CATEGORY_OPTIONS: { value: ExpenseCategory; label: string }[] = [
  { value: 'MATERIALS', label: 'Materials' },
  { value: 'RENT', label: 'Rent' },
  { value: 'UTILITIES', label: 'Utilities' },
  { value: 'OTHER', label: 'Other' },
  { value: 'MANAGER_TIME', label: 'Manager time' },
];

export const EXCLUDE_REASON_OPTIONS: { value: ExcludeReason; label: string }[] = [
  { value: 'TRANSFER', label: 'Transfer between own accounts' },
  { value: 'CREDIT_CARD_PAYMENT', label: 'Credit card payment' },
  { value: 'PAYROLL', label: 'Payroll / payout' },
  { value: 'TAX', label: 'Tax payment' },
  { value: 'OWNER_CONTRIBUTION', label: 'Owner contribution' },
  { value: 'CASH_WITHDRAWAL', label: 'Cash withdrawal' },
  { value: 'REFUND', label: 'Refund' },
  { value: 'OTHER', label: 'Other (not an expense)' },
];

const EXCLUDE_SENTINEL = '__EXCLUDE__';

export interface CategorySelection {
  category?: ExpenseCategory;
  excludeReason?: ExcludeReason;
}

/** The existing ExpenseCategory list, plus an Exclude option with its own reason sub-select
 * (openspec design.md §9/D8) — reused by both the per-row picker and the bulk action bar. */
export default function CategorySelect({
  value, onChange, disabled,
}: {
  value: CategorySelection;
  onChange: (value: CategorySelection) => void;
  disabled?: boolean;
}) {
  const mainValue = value.excludeReason ? EXCLUDE_SENTINEL : (value.category ?? '');

  return (
    <div className="flex flex-wrap items-center gap-1.5">
      <select
        value={mainValue}
        disabled={disabled}
        onChange={(e) => {
          if (e.target.value === EXCLUDE_SENTINEL) {
            onChange({ excludeReason: EXCLUDE_REASON_OPTIONS[0].value });
          } else {
            onChange({ category: (e.target.value || undefined) as ExpenseCategory | undefined });
          }
        }}
        className="rounded border border-zinc-300 px-1.5 py-1 text-xs"
      >
        <option value="" disabled>Choose category…</option>
        {CATEGORY_OPTIONS.map((c) => (
          <option key={c.value} value={c.value}>{c.label}</option>
        ))}
        <option value={EXCLUDE_SENTINEL}>Exclude…</option>
      </select>
      {value.excludeReason && (
        <select
          value={value.excludeReason}
          disabled={disabled}
          onChange={(e) => onChange({ excludeReason: e.target.value as ExcludeReason })}
          className="rounded border border-amber-300 bg-amber-50 px-1.5 py-1 text-xs text-amber-800"
        >
          {EXCLUDE_REASON_OPTIONS.map((r) => (
            <option key={r.value} value={r.value}>{r.label}</option>
          ))}
        </select>
      )}
    </div>
  );
}
