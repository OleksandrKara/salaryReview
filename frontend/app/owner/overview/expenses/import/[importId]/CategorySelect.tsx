import type { ExcludeReason, ExpenseCategory, ExpenseCategoryDefinition } from '../../../../../lib/types';

export const EXCLUDE_REASON_OPTIONS: { value: ExcludeReason; label: string }[] = [
  { value: 'TRANSFER', label: 'Transfer between own accounts' },
  { value: 'CREDIT_CARD_PAYMENT', label: 'Credit card payment' },
  { value: 'PAYROLL', label: 'Payroll / payout' },
  { value: 'TAX', label: 'Tax payment' },
  { value: 'OWNER_CONTRIBUTION', label: 'Owner contribution' },
  { value: 'CASH_WITHDRAWAL', label: 'Cash withdrawal' },
  { value: 'REFUND', label: 'Refund' },
  { value: 'DEPOSIT', label: 'Deposit (money in)' },
  { value: 'OTHER', label: 'Other (not an expense)' },
];

const EXCLUDE_SENTINEL = '__EXCLUDE__';

export interface CategorySelection {
  category?: ExpenseCategory;
  excludeReason?: ExcludeReason;
}

/** The owner's editable category list (see /owner/overview/expenses/categories), plus an Exclude
 * option with its own reason sub-select (openspec design.md §9/D8) — reused by both the per-row
 * picker and the bulk action bar. */
export default function CategorySelect({
  value, onChange, disabled, categories,
}: {
  value: CategorySelection;
  onChange: (value: CategorySelection) => void;
  disabled?: boolean;
  categories: ExpenseCategoryDefinition[];
}) {
  const mainValue = value.excludeReason ? EXCLUDE_SENTINEL : (value.category ?? '');

  return (
    // Stacked full-width on mobile, side by side from sm: up — a native <select> sizes its box to
    // its selected option's text and never wraps, so laid out with `flex-wrap` alone a long
    // category/exclude-reason label could push this wider than its mobile card (the classic
    // flex-item min-width:auto overflow). min-w-0 lets each select actually shrink to fit instead.
    <div className="flex w-full min-w-0 flex-col gap-1.5 sm:w-auto sm:flex-row sm:flex-wrap sm:items-center">
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
        className="w-full min-w-0 rounded border border-zinc-300 px-1.5 py-1 text-xs sm:w-auto sm:max-w-[13rem]"
      >
        <option value="" disabled>Choose category…</option>
        {categories.map((c) => (
          <option key={c.code} value={c.code}>{c.label}</option>
        ))}
        <option value={EXCLUDE_SENTINEL}>Exclude…</option>
      </select>
      {value.excludeReason && (
        <select
          value={value.excludeReason}
          disabled={disabled}
          onChange={(e) => onChange({ excludeReason: e.target.value as ExcludeReason })}
          className="w-full min-w-0 rounded border border-amber-300 bg-amber-50 px-1.5 py-1 text-xs text-amber-800 sm:w-auto sm:max-w-[13rem]"
        >
          {EXCLUDE_REASON_OPTIONS.map((r) => (
            <option key={r.value} value={r.value}>{r.label}</option>
          ))}
        </select>
      )}
    </div>
  );
}
