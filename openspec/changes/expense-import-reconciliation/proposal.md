## Why

The Expenses tab (`/owner/overview/expenses`) is entirely manual today: `ExpenseEntryForm` requires
the owner to type in category, period, amount, and an optional note for every single expense, one
at a time. That's fine for a handful of recurring lump-sum entries (rent, a manager-time backfill)
but does not scale to the owner's actual business bank account, which produces 50-200+ individual
transactions a month across materials, utilities, subscriptions, and a long tail of one-offs. The
owner currently either skips most of these (understating real cost, overstating Net Revenue) or
faces an hour-plus of manual entry every month that only gets worse as the business grows.

The owner already has monthly CSV statements available from their bank — the same shape of
artifact QuickBooks reconciliation is built around. The ask is not "add a CSV importer" so much as
"replace repetitive categorization with a system that gets smarter every month," so that the
owner's only recurring task becomes reviewing the handful of transactions the system genuinely
isn't sure about.

This sits directly next to the Net Revenue work already shipped (`ExpenseEntry`, `ExpenseResolver`,
the Gross/Net/Expenses tab split) — the design goal is for every dollar this feature reconciles to
become an ordinary `expense_entries` row, so `NetSummary`/`NetTable`/`GrowthTable` need **zero**
changes to pick it up.

## What Changes

- **New "Import Statement" flow on the Expenses tab**, alongside (not replacing) the existing
  manual `ExpenseEntryForm` — some expenses (manager-time backfill, a landlord invoice not yet
  reflected on the statement) will always be entered by hand.
- **CSV parsing** of a single business bank account's monthly statement into individual
  transactions, with cross-import duplicate detection (re-uploading the same or an overlapping
  statement never double-counts).
- **Deterministic merchant normalization** that collapses bank-specific noise (`SQ *`, POS/CHECKCARD
  prefixes, trailing reference numbers, city/state suffixes) so `SQ *AKLUXNAILS`, `SQ AKLUXNAILS`,
  and `Square AKLUXNAILS` all resolve to the same canonical merchant — see design.md D2.
- **A five-tier rule engine** (fingerprint → merchant → merchant+keyword → merchant+amount-range →
  fuzzy-similarity → manual) that auto-categorizes high-confidence transactions and routes
  everything else to review, with every automatic decision carrying a plain-English "matched
  because…" explanation — see design.md D4/D5.
- **Continuous learning**: every manual category decision optionally becomes a rule (with an
  explicit, visible warning before it silently changes future behavior for that merchant — see
  design.md D6), so next month's import needs less review than this month's.
- **A reconciliation review screen** in the same visual language as the rest of the app (mobile
  cards / desktop table, the same badge and bulk-select conventions already used on the Contacts
  tab and `PrepaidManager`) — grouped into Needs Review (expanded), Automatically Categorized
  (collapsed by default), and Excluded/Duplicates (collapsed), with search, filters, and bulk
  category assignment.
- **A dedicated Merchant Rules screen** to view, edit, or delete any learned rule directly, not
  only reactively through a transaction correction.
- **Import history**: every uploaded file, its parsed transactions, and the reconciliation decision
  on each one are kept — an import can be reopened, and a whole import (not individual reconciled
  transactions) can be reverted, deleting the `expense_entries` rows it produced.
- **A non-expense "Exclude" path** for transfers, credit-card payments, payroll runs, tax payments,
  owner contributions, and cash withdrawals — these get reconciled (so they leave the review queue
  and the decision is remembered) without ever becoming an `expense_entries` row, so Net Revenue is
  never inflated by internal money movement — see design.md D8.

## Non-goals

- **No AI/LLM in the MVP.** Every categorization decision must be explainable by a deterministic
  rule a human can read and debug. The architecture leaves room for a future confidence-scoring or
  suggestion layer (see design.md D16 / tasks.md §9), but nothing in this change calls a model.
- **No multi-bank-account support.** The app "currently supports a single business bank account"
  per the ask; the schema is *shaped* to extend to more than one later (see design.md D1) but the
  MVP UI has no account picker and assumes one ongoing statement series.
- **No reconciliation against the existing payroll/commission engine.** A payroll ACH batch line on
  the bank statement is excluded from expenses (design.md D8) — this feature does not attempt to
  match it against `PeriodEntry`/`CommissionCalculator` data.
- **No general multi-bank CSV-format auto-detection.** MVP assumes one configurable column mapping
  (date/description/amount, with a debit/credit-column fallback) for the one account in use; a
  format picker for arbitrary bank exports is a future enhancement (tasks.md §9).
- **No background/async job queue.** At the stated volume (100-2,000 rows/import) parsing +
  auto-matching runs synchronously within the upload request; a queue is a future enhancement if
  multi-year backfills prove too slow (design.md D14).
- **No new Expense category.** This reuses the existing `MATERIALS` / `RENT` / `UTILITIES` /
  `OTHER` / `MANAGER_TIME` set exactly as-is; if the owner later wants a `TAXES` category that's a
  separate, owner-driven change, not something this feature invents unilaterally.

## Capabilities

### New Capabilities

- `expense-statement-import` (salaryReview backend): CSV upload, parsing, fingerprinting,
  cross-import duplicate detection, raw-file retention, import-session lifecycle (including
  revert).
- `merchant-rule-engine` (salaryReview backend): merchant normalization/alias pipeline, the
  five-tier priority matching algorithm, confidence scoring, match-reason generation, and the
  learning/rule-mutation behavior triggered by manual review decisions.
- `expense-reconciliation-workspace` (salaryReview frontend): the upload screen, the reconciliation
  review screen (grouped list, search/filter, bulk actions, confidence badges, match-reason tips),
  the Merchant Rules management screen, and the Import History screen.

### Modified Capabilities

*(none — the existing `expense_entries` schema, `ExpenseResolver`, `ExpenseService`, and the
Net/Gross/Expenses tabs are consumed exactly as they exist today; this change only ever calls
`ExpenseService.createExpenseEntry`/`deleteExpenseEntry`, it doesn't change their contracts.)*

## Impact

- **Backend (salaryReview)**: new migration(s) starting at **V65** (`bank_statement_imports`,
  `bank_transactions`, `merchant_rules`, `merchant_aliases` — see design.md D1); new
  `CsvStatementParser`, `MerchantNormalizer`, `MerchantRuleEngine`, `ExpenseImportService`
  (orchestration + revert), `MerchantRuleService`; new `ExpenseImportController` and
  `MerchantRuleController` under `/api/owner/expenses/**`, gated by the existing `/api/owner/**`
  OWNER-only catch-all in `SecurityConfig` — no new security rule needed. New dependency:
  `org.apache.commons:commons-csv` (no CSV library exists in this codebase yet). Reuses the
  existing `MultipartFile` upload pattern from `StaffDocumentController` (bytes stored directly in
  Postgres, no filesystem/object storage) for the raw statement file.
- **Frontend**: new routes under `frontend/app/owner/overview/expenses/` — `import/`,
  `import/[importId]/` (the reconciliation screen), `history/`, `rules/` — reusing the existing
  `RevenueTabs`, the mobile-card/desktop-table responsive pattern from `ContactsTable`, the badge/
  pill styling from `ContactInfoPanel`, the bulk-select pattern from `PrepaidManager`, and
  `InfoTip` for match-reason explanations. `ExpenseEntryForm.tsx` and `expenses/page.tsx` gain an
  entry point to the new flow but are otherwise unchanged.
- **No changes** to `ExpenseResolver`, `ExpenseService`'s existing methods, `NetSummary`,
  `NetTable`, `GrowthTable`, or the `expense_entries` table/CHECK constraint — every reconciled,
  non-excluded transaction becomes an ordinary `expense_entries` row via the existing
  `createExpenseEntry` call (design.md D3).
