# Expense Import & Reconciliation — Design

This covers every technical deliverable requested for this change. Section numbers in brackets
map back to the original ask so nothing gets lost; some deliverables (Functional Specification,
Business Requirements) live primarily in `proposal.md` and the `specs/*/spec.md` files rather than
being repeated here.

---

## 1. User Stories [3]

- **As the owner**, I upload this month's bank CSV and, within seconds, see most transactions
  already categorized — I only touch the ones the system flags.
- **As the owner**, when I correct a category, I want the system to ask whether to remember that
  for next time, and to tell me plainly what will change if I say yes.
- **As the owner**, I want to see *why* a transaction was auto-categorized, not just trust a black
  box — if something looks wrong, I want to fix it in two taps and know the fix sticks.
- **As the owner**, I want a running count of what's left to review, not a wall of 400 rows to
  scroll through.
- **As the owner**, if I upload the wrong file or the same statement twice, I want the system to
  catch it, not silently double my expenses.
- **As the owner**, if I mess up a whole import, I want to undo it cleanly rather than manually
  hunting down and deleting expense rows.
- **As the owner**, I want this to work as well on my phone between doing a client's nails as it
  does at a desk at night.

## 2. UX Flow [4]

```
Expenses tab
  └─ "Import Statement" (new, next to the existing manual entry form)
       └─ Upload screen: pick file → upload → parse (sync, <2s typical)
            └─ Reconciliation Review screen (this import's transactions)
                 ├─ Needs Review (expanded) — the owner's actual work
                 ├─ Automatically Categorized (collapsed, expandable to spot-check)
                 ├─ Excluded (collapsed) — transfers, payroll, etc.
                 └─ Duplicates skipped (collapsed) — transparency, no action needed
                      └─ "Complete Reconciliation" → writes expense_entries, import → COMPLETED
                           └─ back to Expenses tab — Net/Gross tabs already reflect the new total
  └─ "Import History" — past imports, reopen / re-download original file / revert
  └─ "Merchant Rules" — every learned rule, editable/deletable directly
```

Every screen after upload answers three questions at a glance (the UX principle the ask calls
out): *what happened, why, what's left.*

## 3. Wireframes — Desktop [5]

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ Revenue     Gross | Net | Expenses                                          │
├─────────────────────────────────────────────────────────────────────────────┤
│ Reconcile: August statement (aklux_checking_2026-08.csv)     [Import History]│
│                                                                               │
│ 214 transactions · 189 auto-categorized · 18 need review · 4 excluded ·      │
│ 3 duplicates skipped                              [ Complete Reconciliation ]│
│                                                                               │
│ [ Search merchant/description...      ] [ Status ▾ ] [ Category ▾ ]          │
│                                                                               │
│ ▾ Needs Review (18)                              [ Select all ] [Bulk: ▾ Go] │
│ ┌───┬────────────┬─────────────────────┬─────────┬──────────┬─────────────┐ │
│ │ ☐ │ Aug 14     │ WM SUPERCENTER #4821│ $84.12  │⚠ Unsure  │[Category ▾] │ │
│ │   │            │ possible: COSTCO 71%│         │ (i)      │             │ │
│ ├───┼────────────┼─────────────────────┼─────────┼──────────┼─────────────┤ │
│ │ ☐ │ Aug 15     │ CHECK #1042         │ $650.00 │❌ Unknown│[Category ▾] │ │
│ └───┴────────────┴─────────────────────┴─────────┴──────────┴─────────────┘ │
│                                                                               │
│ ▸ Automatically Categorized (189)                                            │
│ ▸ Excluded (4)                                                               │
│ ▸ Duplicates skipped (3)                                                     │
└─────────────────────────────────────────────────────────────────────────────┘
```

The collapsed sections show their count in the header, matching the collapsed-by-default,
expand-to-verify pattern; nothing is hidden, just deprioritized.

## 4. Wireframes — Tablet [6]

Same structure, columns compress: the raw-description second line and the confidence icon share
a row instead of a wide gutter; the category `<select>` moves under the row instead of alongside
it. Bulk-select bar becomes sticky at the bottom of the viewport instead of inline with the
section header (thumb reach).

```
┌───────────────────────────────────────────┐
│ Reconcile: August statement                │
│ 189 auto · 18 review · 4 excl · 3 dup       │
│ [ Complete Reconciliation ]                 │
│ [ Search... ] [Status ▾][Category ▾]        │
│ ▾ Needs Review (18)                         │
│ ┌─────────────────────────────────────────┐ │
│ │ Aug 14 · $84.12                ⚠ (i)     │ │
│ │ WM SUPERCENTER #4821                     │ │
│ │ possible: COSTCO (71% similar)           │ │
│ │ [ Category ▾ ]                           │ │
│ └─────────────────────────────────────────┘ │
│ ▸ Automatically Categorized (189)           │
├─────────────────────────────────────────────┤
│ ☐ 3 selected     [ Assign category ▾ ] [Go] │ ← sticky bulk bar
└─────────────────────────────────────────────┘
```

## 5. Wireframes — Mobile [7]

Full card layout (no table at all below `sm`, matching `ContactsTable`'s existing
`flex flex-col gap-3 sm:hidden` convention). One primary action per card; category assignment is a
single tap that opens a bottom-sheet-style select, not a long dropdown scroll.

```
┌───────────────────────────┐
│ August statement           │
│ 18 need review · 189 auto  │
│ [ Complete Reconciliation ]│
├───────────────────────────┤
│ [ Search... ]        [≡]   │ ← filters behind a sheet, not inline chips
├───────────────────────────┤
│ ⚠ Needs Review (18)        │
│ ┌─────────────────────────┐│
│ │ Aug 14        $84.12    ││
│ │ WM SUPERCENTER #4821    ││
│ │ possible: COSTCO (71%) (i)│
│ │ [ Choose category ▾ ]   ││
│ └─────────────────────────┘│
│ ▸ Automatically Categorized ││
│   (189) — tap to expand    │
└───────────────────────────┘
```

Minimizing typing/clicks (an explicit ask): every review action is select-from-list, never free
text, except the one-time "add a keyword to disambiguate" advanced path.

## 6. Screen Flow Diagram [8]

```
[Expenses tab] ──upload──▶ [Upload screen] ──parse──▶ [Reconciliation Review] ──complete──▶ [Expenses tab]
      │                                                      │  ▲
      │                                                      │  │ reopen (not yet completed,
      │                                                      ▼  │ or completed-but-editable window)
      ├──history──▶ [Import History] ───────────────────────────┘
      │                    │
      │                    └──revert──▶ (deletes linked expense_entries, resets transactions,
      │                                  import → REVERTED)
      └──rules───▶ [Merchant Rules] ◀── "remember this" from any review-screen correction
```

## 7. Component Hierarchy [9]

```
frontend/app/owner/overview/expenses/
├── page.tsx                         (existing — gains an "Import statement" entry point)
├── ExpenseEntryForm.tsx              (existing — unchanged)
├── import/
│   └── page.tsx                     (new — upload screen; server component + client uploader)
│       └── StatementUploadForm.tsx   (new — file input, drag/drop, progress state)
├── import/[importId]/
│   └── page.tsx                     (new — reconciliation review, server-fetches the import)
│       └── ReconciliationWorkspace.tsx  (new — client component, owns filter/search/selection state)
│           ├── ImportSummaryHeader.tsx  (counts + Complete Reconciliation button)
│           ├── TransactionFilterBar.tsx (search + status + category filters)
│           ├── TransactionSection.tsx   (one per status group; collapsible)
│           │   └── TransactionRow.tsx   (dual-render: mobile card / desktop table row)
│           │       ├── ConfidenceBadge.tsx   (✅ / ⚠ / ❌, reused across sections)
│           │       ├── MatchReasonTip.tsx    (wraps existing InfoTip)
│           │       └── CategorySelect.tsx    (reuses ExpenseCategory list + "Exclude")
│           └── BulkActionBar.tsx        (reuses PrepaidManager's picked-Set pattern)
├── history/
│   └── page.tsx                     (new — list of ImportSummary rows, status, revert action)
└── rules/
    └── page.tsx                     (new — MerchantRulesTable.tsx: list/edit/delete rules)
```

Backend mirrors the same shape (see §12).

## 8. UX Rationale [10]

- **Progressive disclosure**: auto-categorized transactions are real, visible data — never hidden
  — but collapsed by default so the owner's eye goes straight to what needs a decision. This is
  the single biggest lever on "spend less time than last month" (the stated success metric):
  review time scales with the *review queue*, not total transaction count.
- **Explainability over trust**: every automatic decision carries a plain-English reason
  (design.md §16) reachable in one tap via the existing `InfoTip` pattern — the owner never has to
  take an automatic category on faith, which is what makes it safe to *not* review the collapsed
  section every month.
- **Three states, not a spectrum**: confidence collapses to exactly ✅ / ⚠ / ❌ in the UI (never a
  raw percentage) — a percentage invites second-guessing a well-calibrated auto-match; a
  three-state badge tells the owner exactly what to do with each transaction (nothing / decide /
  decide).
- **Bulk over one-at-a-time**: the ask explicitly calls for supporting bulk operations "whenever
  they save time" — the review queue is exactly that case, since a batch of Needs Review rows
  often shares one obvious correct category (e.g. five gas-station charges in one statement).
- **Minimize typing**: every category decision is select-from-list; free text exists only in the
  optional advanced "disambiguate by keyword" path, and never blocks the primary flow.
- **Undo at the right granularity**: whole-import revert (design.md D10), not per-transaction undo
  — matches how the owner actually thinks about a mistake ("that whole statement is wrong")
  without the complexity of tracking a stack of granular undo/redo for every individual field edit.

---

## Database Schema Changes [11]

New migration **V65** (next after V64 — `shedlock`).

**D1 — single account now, shaped for more later.** The MVP has no account picker and no
`bank_account_id` column anywhere below — every table implicitly means "the salon's one
account." Nothing here hard-codes that assumption in a way that would force a rework: imports,
rules, and transactions are already scoped by their own surrogate keys rather than by any
account-identifying value, so adding a `bank_account_id BIGINT` column to
`bank_statement_imports` (and threading it through as an optional filter) later is a purely
additive migration, not a redesign. The alternative — inferring "the account" from some global
singleton row — was rejected because it's no simpler to write today and would need an actual
schema change (not just an added column) to lift later. Nothing in this section should be read
as a commitment to single-account forever; it's a deliberate scope cut for MVP only (see
proposal.md Non-goals).

```sql
-- V65__expense_import_reconciliation.sql

CREATE TABLE bank_statement_imports (
    id                      BIGSERIAL PRIMARY KEY,
    original_filename       TEXT NOT NULL,
    raw_file                BYTEA NOT NULL,             -- same pattern as StaffDocument.fileData
    row_count               INT NOT NULL,
    statement_period_start  DATE,
    statement_period_end    DATE,
    status                  TEXT NOT NULL DEFAULT 'AWAITING_REVIEW'
                             CHECK (status IN ('AWAITING_REVIEW','COMPLETED','REVERTED')),
    uploaded_by             VARCHAR(100),
    uploaded_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at            TIMESTAMPTZ,
    reverted_at             TIMESTAMPTZ
);

CREATE TABLE merchant_aliases (
    id                BIGSERIAL PRIMARY KEY,
    raw_pattern       TEXT NOT NULL UNIQUE,   -- a specific raw/normalized-once descriptor variant
    canonical_merchant TEXT NOT NULL,         -- the merchant it should resolve to instead
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE merchant_rules (
    id                 BIGSERIAL PRIMARY KEY,
    rule_type          TEXT NOT NULL CHECK (rule_type IN
                        ('FINGERPRINT','MERCHANT','MERCHANT_KEYWORD','MERCHANT_AMOUNT_RANGE')),
    normalized_merchant TEXT NOT NULL,
    keyword            TEXT,                  -- MERCHANT_KEYWORD only
    amount_min         NUMERIC(10,2),          -- MERCHANT_AMOUNT_RANGE only
    amount_max         NUMERIC(10,2),
    fingerprint        TEXT,                   -- FINGERPRINT only (merchant+amount+description hash)
    category           TEXT NOT NULL,          -- reuses ExpenseEntry's category values, incl. an
                                                -- 'EXCLUDE_<reason>' pseudo-value (see D8)
    active             BOOLEAN NOT NULL DEFAULT true,
    created_by         VARCHAR(100),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    times_applied      INT NOT NULL DEFAULT 0,
    last_applied_at    TIMESTAMPTZ,
    source_transaction_id BIGINT              -- traceability; FK added after bank_transactions exists
);
-- Enforces "at most one plain merchant-level default" (D9) — the conflict that forces the
-- disambiguation prompt described in the Costco example.
CREATE UNIQUE INDEX idx_merchant_rules_one_default_per_merchant
    ON merchant_rules (normalized_merchant)
    WHERE rule_type = 'MERCHANT' AND active;
CREATE INDEX idx_merchant_rules_lookup ON merchant_rules (normalized_merchant, rule_type, active);

CREATE TABLE bank_transactions (
    id                    BIGSERIAL PRIMARY KEY,
    import_id             BIGINT NOT NULL REFERENCES bank_statement_imports(id),
    transaction_date      DATE NOT NULL,
    raw_description        TEXT NOT NULL,
    normalized_merchant    TEXT NOT NULL,
    merchant_key           TEXT NOT NULL,       -- for trigram similarity (D2)
    amount                 NUMERIC(10,2) NOT NULL,   -- signed; negative = money out
    fingerprint             TEXT NOT NULL,
    occurrence_index        INT NOT NULL DEFAULT 0,   -- disambiguates true same-day repeats (D7)
    status                  TEXT NOT NULL DEFAULT 'UNMATCHED' CHECK (status IN
                             ('UNMATCHED','AUTO_MATCHED','NEEDS_REVIEW','REVIEWED','EXCLUDED','DUPLICATE')),
    matched_rule_id          BIGINT REFERENCES merchant_rules(id),
    match_reason             TEXT,               -- human-readable, D5
    confidence               NUMERIC(3,2),        -- 0.00–1.00, null when Unknown
    category                 TEXT,               -- set once AUTO_MATCHED or REVIEWED
    excluded_reason          TEXT CHECK (excluded_reason IN
                              ('TRANSFER','CREDIT_CARD_PAYMENT','PAYROLL','TAX',
                               'OWNER_CONTRIBUTION','CASH_WITHDRAWAL','REFUND','OTHER')),
    linked_expense_entry_id  BIGINT REFERENCES expense_entries(id),
    duplicate_of_transaction_id BIGINT REFERENCES bank_transactions(id),
    reviewed_by              VARCHAR(100),
    reviewed_at              TIMESTAMPTZ
);
CREATE INDEX idx_bank_transactions_import ON bank_transactions (import_id);
CREATE INDEX idx_bank_transactions_status ON bank_transactions (import_id, status);
CREATE INDEX idx_bank_transactions_fingerprint ON bank_transactions (fingerprint);
CREATE INDEX idx_bank_transactions_merchant ON bank_transactions (normalized_merchant);

-- Needed for merchant_key fuzzy matching (D2) — Postgres's own well-understood trigram similarity,
-- not a black-box embedding.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_bank_transactions_merchant_trgm
    ON bank_transactions USING gin (merchant_key gin_trgm_ops);

ALTER TABLE merchant_rules
    ADD CONSTRAINT fk_merchant_rules_source_transaction
    FOREIGN KEY (source_transaction_id) REFERENCES bank_transactions(id);
```

No changes to `expense_entries`' schema or CHECK constraint (D3) — `bank_transactions.category`
uses the exact same string values already validated there; a real (non-excluded) reconciled
transaction is inserted into `expense_entries` with those same values.

**D8 — the Exclude path never touches `expense_entries`.** Setting `excluded_reason` to any of
the eight values above (or completing a reconciliation with a transaction left in that state)
never creates, updates, or deletes an `expense_entries` row for that transaction — not now, not
retroactively if the reason is later changed to another exclude reason. An excluded transaction
stays fully visible and reviewable in the workspace (it's a `bank_transactions` row like any
other, just with `status = 'EXCLUDED'`), and its category can still be corrected later, but the
one guarantee that holds unconditionally is that it can never, by itself, move Net Revenue. This
is why transfers, credit-card payments, payroll, tax, owner contributions, and cash withdrawals
(all Edge Cases [20]) are modeled as exclude reasons rather than categories: an owner can
misclassify a category and correct it later without financial impact in the meantime, but an
exclude reason is the one classification the schema itself refuses to let leak into an expense
total. This is enforced structurally, not just by convention: `ExpenseImportService.
completeReconciliation` (§4.2 in tasks.md) only iterates non-excluded, non-duplicate
transactions when calling `ExpenseService.createExpenseEntry`, so there is no code path from an
`EXCLUDED` row to an `expense_entries` insert. Formalized as the
`expense-reconciliation-workspace` spec's "Excluded transactions never affect Net Revenue"
scenario.

## Backend Architecture [12]

```
com.salonreview.expense
├── domain/
│   ├── BankStatementImport.java
│   ├── BankTransaction.java
│   ├── MerchantRule.java
│   └── MerchantAlias.java
├── repo/
│   ├── BankStatementImportRepository.java
│   ├── BankTransactionRepository.java
│   ├── MerchantRuleRepository.java
│   └── MerchantAliasRepository.java
├── CsvStatementParser.java       (§13)
├── MerchantNormalizer.java       (§14, D2)
├── MerchantRuleEngine.java       (§15, D4/D9)
├── ExpenseImportService.java     (orchestration: import, complete, revert — D3/D10)
├── MerchantRuleService.java      (CRUD + the "remember this" mutation path — D6)
└── web/
    ├── ExpenseImportController.java   (/api/owner/expenses/imports/**)
    └── MerchantRuleController.java    (/api/owner/expenses/rules/**)
```

Follows this codebase's existing layering exactly (domain → repo → service → controller, same as
`ExpenseEntry`/`ExpenseService`/`ExpenseController`); no new architectural pattern introduced.

## API Design [13]

All under `/api/owner/expenses/...`, inheriting `SecurityConfig`'s existing
`.requestMatchers("/api/users/**", "/api/owner/**", "/api/rag/admin/**").hasRole("OWNER")`
catch-all — no `SecurityConfig` change needed, same as the existing `ExpenseController`.

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/owner/expenses/imports` | Upload a CSV (`multipart/form-data`, field `file`) — parses, fingerprints, auto-matches, persists; returns the new import summary |
| GET  | `/api/owner/expenses/imports` | List past imports (history screen) |
| GET  | `/api/owner/expenses/imports/{id}` | One import + its transactions, grouped by status |
| GET  | `/api/owner/expenses/imports/{id}/file` | Download the original raw CSV (mirrors `StaffDocumentController.download`) |
| PATCH | `/api/owner/expenses/imports/{id}/transactions/{txnId}` | Set/change a transaction's category or exclude reason; optional `rememberForMerchant: boolean` |
| POST | `/api/owner/expenses/imports/{id}/transactions/bulk` | Bulk-apply one category/exclude-reason to a list of transaction ids |
| POST | `/api/owner/expenses/imports/{id}/complete` | Finalize: create `expense_entries` for every reconciled, non-excluded, non-duplicate row; import → `COMPLETED` |
| POST | `/api/owner/expenses/imports/{id}/revert` | Delete the `expense_entries` rows this import created, reset its transactions, import → `REVERTED` |
| GET  | `/api/owner/expenses/rules` | List all merchant rules |
| PUT  | `/api/owner/expenses/rules/{id}` | Edit a rule (category, keyword, amount range, active flag) |
| DELETE | `/api/owner/expenses/rules/{id}` | Delete a rule |

## CSV Parsing Strategy [14]

New dependency: `org.apache.commons:commons-csv` (no CSV library exists in this codebase today).

MVP targets one configurable column mapping for the one business bank account in use — a small
constant, not a UI: `DATE_COLUMN = "Date"`, `DESCRIPTION_COLUMN = "Description"`,
`AMOUNT_COLUMN = "Amount"` (signed, negative = debit), with a fallback path if the file instead has
separate `Debit`/`Credit` columns (common bank-export variant) — detected by header presence, not
guessed from content. A header row is required; a file without a recognizable header set fails
loudly with a specific "couldn't find expected columns" error (never silently misreads).

Per-row processing: parse date/amount, trim/collapse the description, run it through
`MerchantNormalizer` (§14) to get `normalized_merchant` + `merchant_key`, compute
`fingerprint = sha256(date | amount | normalized_merchant)`, then assign `occurrence_index` as the
count of prior rows *in this same import* sharing that exact fingerprint (D7) — the final stored
fingerprint used for duplicate lookups is `fingerprint + ':' + occurrence_index`, so genuine
same-day repeats (two identical $9.99 charges) get distinct keys while a byte-for-byte re-upload of
the same file reproduces the same sequence and is fully caught as duplicate.

## Merchant Normalization Strategy [15] — D2

Deterministic pipeline, each step logged/testable in isolation:

1. Uppercase, trim, collapse internal whitespace.
2. Strip known bank-descriptor noise via a small, explicit regex list — not a black box:
   `^SQ\s?\*?\s?`, `^SQUARE\s+`, `^POS\s+(DEBIT|PURCHASE)\s+`, `^CHECKCARD\s+\d{4}\s+`,
   trailing ` #\d+$`, trailing 2-letter state + city patterns.
3. Strip residual punctuation noise (asterisks, double spaces) but keep alphanumerics and spaces —
   `SQ *AKLUXNAILS` / `SQ AKLUXNAILS` / `SQ* AKLUX NAILS` / `Square AKLUXNAILS` all collapse to
   `AKLUXNAILS`.
4. Check `merchant_aliases` for an exact match on the post-step-3 string; if present, replace with
   its `canonical_merchant` (this is where a merchant rebrand or a bank's own descriptor change gets
   permanently folded into the existing merchant identity, per user confirmation — see the Edge
   Cases entry on merchant name changes).
5. The result is `normalized_merchant` — the join key for Priority-2/3/4 rule matching.
6. `merchant_key` = `normalized_merchant` with all whitespace removed, used only for the
   `pg_trgm` fuzzy-similarity fallback tier (never the primary key) — kept as a separate column so
   the "exact" and "fuzzy" matching paths never accidentally shadow each other.

Deliberately **not** a hardcoded merchant-alias table maintained by developers — the ask is
explicit that the system should "continuously build knowledge from historical reconciliations,"
so `merchant_aliases` is populated by owner action (confirming a fuzzy match), not shipped
pre-seeded with guesses.

## Rule Engine Design & Matching Algorithm [16, 17] — D4, D9

For a transaction with `normalized_merchant` M, description D, amount A:

1. **Fingerprint** (`rule_type = FINGERPRINT`): does an active rule exist whose stored fingerprint
   equals this transaction's `sha256(date-agnostic? no — same amount + same normalized merchant +
   same description)` — concretely, a rule created from a transaction with amount A′ and
   description D′ where A′ = A and D′ ≈ D (same normalized merchant, same amount, same or
   near-identical raw description). Confidence **0.99**. Typical case: a fixed-amount recurring
   subscription.
2. **Merchant + keyword** (`MERCHANT_KEYWORD`): an active rule for merchant M whose `keyword`
   appears (case-insensitive substring) in D. Confidence **0.85**.
3. **Merchant + amount range** (`MERCHANT_AMOUNT_RANGE`): an active rule for merchant M where
   `amount_min <= |A| <= amount_max`. Confidence **0.75**.
4. **Plain merchant** (`MERCHANT`): the single active default rule for M (uniqueness-enforced,
   D9). Confidence **0.90** if reached with no keyword/amount rule present at all for M (a clean,
   unambiguous merchant); if reached only because keyword/amount rules exist but none matched
   *this* transaction, confidence drops to **0.60** and the row goes to Needs Review instead of
   auto-applying — a near-miss on a merchant with known nuance is exactly the case that deserves a
   human glance, not a guess.
5. **Fuzzy similarity**: if no rule matched at all, check `pg_trgm` similarity between this
   transaction's `merchant_key` and every other distinct `merchant_key` that has at least one
   active rule; similarity ≥ 0.6 surfaces the best match as a suggestion. Confidence band
   **0.40–0.60**, **always** Needs Review regardless of score (D5) — never auto-applied.
- **Special-cased inputs**: a `raw_description`/`normalized_merchant` that reduces to a pure
  reference-number pattern (`^CHECK\s*#?\d+$`, a bare ACH/wire trace number with no merchant
  string) skips rule evaluation entirely and goes straight to manual review — building a rule
  keyed on a one-time check number would never fire again (Edge Cases, §21).
6. **No match**: `Unknown`, confidence null, Needs Review.

**Every match writes a `match_reason` string**, e.g. `"Matched because: Normalized Merchant =
COSTCO (rule created Jun 3, applied 12 times)"` or `"Matched because: Normalized Merchant = COSTCO
+ description contains 'GAS' (rule created Jul 1)"` — surfaced via `MatchReasonTip` (§9) so no
automatic decision is ever silent, satisfying the explicit "explain WHY" requirement.

**D9 — why plain-merchant uniqueness, and why it resolves the prompt's own Costco example**: the
ask's own scenario ("Costco may always become Office Supplies or Salon Supplies depending on
previous decisions") only works if the system detects the *conflict* the first time it happens,
rather than silently overwriting one learned category with another. Enforcing exactly one active
`MERCHANT`-tier rule per merchant (partial unique index) means: the second time the owner
categorizes Costco differently than the existing rule, `MerchantRuleService` detects the conflict
and the review UI prompts — "You previously set COSTCO → Salon Supplies. Change the general rule to
Office Supplies, or add a more specific rule (by keyword or amount) so both can coexist?" — giving
the owner a real choice instead of unexplained drift, and giving future imports a genuinely
disambiguated Priority-3/4 rule when the merchant really does mean different things.

## Reconciliation Workflow [18] — D3, D6, D10

**D3 — every reconciled transaction is an ordinary `expense_entries` row.** On
`POST .../complete`, for each transaction in `AUTO_MATCHED` or `REVIEWED` status (i.e. has a
category, is not `EXCLUDED` or `DUPLICATE`), `ExpenseImportService` calls the existing
`ExpenseService.createExpenseEntry(category, transactionDate, transactionDate, abs(amount), note =
normalizedMerchant, enteredBy)` unchanged, and stores the resulting id back on
`bank_transactions.linked_expense_entry_id`. `period_start = period_end = transaction_date` — a
single-day period is already exactly how `ExpenseResolver`'s day-overlap proration expects an entry
to look, so **zero changes** to `ExpenseResolver`, `NetSummary`, `NetTable`, or `GrowthTable` are
needed; they simply see more `expense_entries` rows than before. A refund (negative amount, D-note
below) is inserted with a negative `amount`, which `ExpenseResolver`'s plain summation already
nets down correctly — no special-casing required there either.

**D6 — learning is opt-in and visible, never silent.** Any category assignment on the review
screen (single or bulk) shows a checkbox, checked by default: *"Remember this for **{merchant}**
in the future."* Unchecking it applies the decision to only this transaction. If checked and a
conflicting `MERCHANT`-tier rule already exists, the before/after is shown inline before saving
(D9). Bulk assignment applies the same "remember" checkbox once for the whole batch when all
selected rows share a merchant; when a bulk selection spans multiple merchants, the "remember"
option is simply unavailable for that action (each merchant's own rule would need its own
decision) and the UI says so.

**D10 — revert is whole-import, not per-transaction.** `POST .../revert` is `ExpenseImportService`
deleting every `expense_entries` row this import's `linked_expense_entry_id`s point to, resetting
every one of the import's transactions to `UNMATCHED` (rules and match history stay intact — only
the *expense-entries side effect* is undone), and marking the import `REVERTED`. This follows the
same audit-first philosophy `ExpenseEntry`/`AdSpendEntry` already establish
("a corrected re-entry is kept alongside the original") applied at the *import* granularity: a
whole bad import is cleanly undoable, while an individual transaction's category can still be
corrected in place after completion (a `PATCH` on an already-`REVIEWED` transaction re-runs the
category through the same `ExpenseService` and, if `linked_expense_entry_id` is already set, calls
`updateExpenseEntry` — the existing "fixing an outright mistake" path — rather than creating a
duplicate row).

## Import History Design [19]

`bank_statement_imports` *is* the import history — no separate table needed. The history screen
lists every import (filename, upload date, status, transaction count, reviewed/needs-review split)
with actions: **Reopen** (if `AWAITING_REVIEW`, jump back into the reconciliation screen exactly
where it was left — nothing about the workflow requires finishing in one sitting), **Revert** (if
`COMPLETED`, per D10), **Download original file** (streams back the stored `raw_file` bytes, same
mechanism as `StaffDocumentController.download`). Re-importing the same statement is not blocked at
upload time (the owner may genuinely want to re-run it, e.g. after a normalization bug fix) — every
transaction in the new import is compared against the whole historical `bank_transactions` table by
fingerprint, so a full re-upload comes back entirely `DUPLICATE` and completing it is a safe no-op.

## Edge Cases [20]

| Case | Handling |
|---|---|
| Refunds | Stored with the CSV's own sign (typically positive on an otherwise-debit-negative statement); if it matches an existing expense merchant, reduces that category's total naturally via `ExpenseResolver`'s summation (D3) — no special-casing. |
| Duplicate imports (same file twice) | Every row's fingerprint already exists → all `DUPLICATE`, completing is a no-op (§19). |
| Duplicate transactions within one file | `occurrence_index` (§14) disambiguates true same-day repeats from an export glitch. |
| Recurring subscriptions | Fixed-amount ones hit the Fingerprint tier natively; variable-amount ones hit plain Merchant — no special modeling needed. |
| Credit card payments | Suggested (not auto-applied) `EXCLUDED / CREDIT_CARD_PAYMENT` via a description-pattern heuristic (`PAYMENT`, card-issuer names); always confirmed once per merchant like any other decision, then remembered. |
| Transfers between own accounts | `EXCLUDED / TRANSFER`, same confirm-once-remember-after pattern. |
| Payroll | `EXCLUDED / PAYROLL` — explicit non-goal (proposal.md) to reconcile against the commission engine; a payroll ACH batch line is excluded, not modeled. |
| Tax payments | `EXCLUDED / TAX` — this app doesn't model tax as an expense category today; not invented here either. |
| Owner contributions | `EXCLUDED / OWNER_CONTRIBUTION`. |
| Checks | Reference-number-only descriptions skip rule-matching entirely (§16), always manual review; no rule is ever built from a check number. |
| ACH / wire transfers | If the description carries a real counterparty name, treated like any other merchant (rules apply normally); if purely a reference number, same as checks. |
| Cash withdrawals | Suggested `EXCLUDED / CASH_WITHDRAWAL` by pattern (`ATM WITHDRAWAL`), confirmed once, remembered — some salons do track petty-cash-for-supplies, so this is a suggestion, never forced. |
| Merchant name changes | Fuzzy-match tier surfaces "possible match: X (N%)"; confirming can create a new rule OR alias the new descriptor permanently to the existing merchant via `merchant_aliases` (§15). |

## Performance [not separately numbered — supports §22–23]

At 100–2,000 rows/import: CSV parse + normalize + rule-lookup is a single synchronous request; rule
lookup is an indexed query per distinct merchant (not per row — merchants repeat heavily within a
statement), and the `pg_trgm` GIN index keeps the fuzzy fallback fast even as
`bank_transactions` grows across years. `idx_bank_transactions_import`/`_status` keep the
reconciliation screen's grouped queries and multi-year history search/filter (a stated requirement)
index-backed rather than full-scans.

## Risks & Trade-offs [21]

- **Rule proliferation / silent drift**: mitigated by (a) uniqueness on the plain merchant-level
  rule (forces a conflict prompt instead of a second silent rule), (b) always showing the
  before/after when a correction would change a rule, (c) a dedicated rules screen for audit and
  cleanup, (d) `times_applied`/`last_applied_at` so stale/unused rules are visible and prunable in
  the future (not in MVP UI, but the data exists).
- **False-positive auto-categorization**: bounded by the 0.75 threshold being conservative and by
  auto-categorized rows staying fully visible/expandable, never silently finalized without the
  owner completing the reconciliation step.
- **CSV format drift** (bank changes its export format): the single hardcoded column mapping
  (§13) will break loudly (parse error, not silent misread) if the bank changes headers — an
  explicit accepted trade-off for MVP scope; a format-mapping UI is a future enhancement (§20).
- **PII/financial data at rest**: raw statement bytes and every transaction description live in
  Postgres, same trust boundary as everything else in this app (no new exposure surface, no new
  external service); OWNER-only gating matches the existing Expense feature exactly.
- **Getting the exclude-vs-categorize call wrong**: a mis-excluded real expense silently
  understates Net Revenue with no error — mitigated by exclusions being just as visible/reviewable
  as categorizations (their own section, not deleted), and by requiring the same explicit
  confirm-and-remember flow as any other decision.
- **Synchronous import at scale (D14)**: parsing + rule-matching runs inline within the upload
  request (no job queue) — an accepted trade-off at the stated 100–2,000 rows/import volume (see
  Performance, above). If a future multi-year backfill import proves slow enough to risk a request
  timeout, the fix is additive — move `ExpenseImportService.importStatement` behind a queue/worker
  and have the upload screen poll or subscribe for completion — without changing the schema, the
  parsing/normalization/rule-engine logic, or any API contract; nothing about the synchronous MVP
  path needs to be undone to add this later.

## Testing Strategy [22]

- **`MerchantNormalizer`**: table-driven unit tests, including the exact prompt example set
  (`SQ *AKLUXNAILS`, `SQ AKLUXNAILS`, `SQ* AKLUX NAILS`, `Square AKLUXNAILS` → same canonical
  form), plus prefix/suffix stripping cases (POS DEBIT, CHECKCARD, trailing city/state, trailing
  reference numbers).
- **Fingerprinting**: same inputs → same fingerprint; different date/amount/description →
  different fingerprint; true same-day repeats get distinct occurrence-indexed fingerprints so
  they are *not* wrongly deduped; a byte-identical re-upload *is* fully deduped.
- **`MerchantRuleEngine`**: one test per priority tier in isolation, plus conflict scenarios —
  keyword rule beats plain-merchant rule for a matching description; amount-range rule beats
  plain-merchant when in-range and falls through to plain-merchant when out-of-range; fuzzy match
  never returns confidence ≥ 0.75 regardless of similarity score; no rule at all → `Unknown`.
- **`ExpenseImportService`**: parse-and-persist round-trip on a fixture CSV; duplicate detection
  against a previously-imported fixture; `completeReconciliation` creates exactly one
  `expense_entries` row per non-excluded, non-duplicate transaction with the right amount sign;
  `revertImport` deletes exactly the `expense_entries` rows it created and resets transaction
  state, leaving unrelated `expense_entries` rows (manual ones, other imports) untouched.
- **Controllers**: OWNER-only gating (mirrors existing `ExpenseControllerTest` pattern); malformed
  CSV returns a clear 4xx, not a 500.
- **Frontend**: component tests for `ConfidenceBadge` threshold rendering, `BulkActionBar` selection
  math (mirrors any existing `PrepaidManager` test if one exists), and a real click-through
  E2E-style check (per this session's established pattern) before merge: upload a small real fixture,
  confirm the review screen groups correctly, confirm completing writes `expense_entries` visible on
  the Net tab.

## MVP Scope [23]

**In scope**: everything in `proposal.md`'s "What Changes" — single-account CSV upload, the
five-tier rule engine, exclusion path, reconciliation review UI (search/filter/bulk/undo-per-import),
merchant rules management, import history with revert.

**Explicitly deferred** (see §20 for the fuller list): multi-account support, configurable
CSV column mapping UI, async/background processing, AI-assisted suggestions, per-transaction
undo (only whole-import revert), automatic credit-card-statement cross-matching, tax-category
modeling.

## Future AI Opportunities [24, non-goal for MVP] — D16

The architecture is shaped so AI is additive, never load-bearing — this is decision **D16**: no
component in this design requires a model to function, and every place AI could plug in later is
an enhancement layer that reads the same deterministic data and never gates a decision.

- **Smarter suggestions**: a suggestion layer could read the same `merchant_rules`/transaction
  history and propose *new* rules the owner hasn't made yet ("you've manually set 4 different
  Home Depot purchases to Materials — want a rule?") — additive, always still reviewed/confirmed
  by the owner, same as any Priority-5 manual decision today.
- **Confidence scoring**: today's confidence is a fixed lookup per tier; a future model could
  produce a continuous score per transaction, feeding the same `confidence` column and the same
  0.75 auto-apply threshold — no schema change needed.
- **Better merchant recognition**: an embedding-based similarity could sit *alongside* (not
  replace) the trigram fuzzy tier, feeding the same "possible match" UI — same explainability
  contract (always surfaced, never silently auto-applied).
- **Anomaly detection**: "this Utilities bill is 3x last month's" — a read-only annotation on top
  of already-reconciled data, not a gate on reconciliation itself.
- **Natural-language explanations**: today's `match_reason` is a template string
  (`"Matched because: Normalized Merchant = COSTCO"`); a model could paraphrase it, but the
  underlying deterministic reason is still what's stored and testable — the model would only ever
  reword, never invent, the reason.
- Mirrors this codebase's own precedent (`suspicious-booking-ai-triage`): a cached, versioned,
  explicitly-labeled AI layer bolted onto a deterministic core, never the only path to a decision.
