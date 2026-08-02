## 1. Backend — schema

- [x] 1.1 Create `V65__expense_import_reconciliation.sql`: `bank_statement_imports`,
      `merchant_aliases`, `merchant_rules` (incl. the partial unique index on
      `(normalized_merchant) WHERE rule_type='MERCHANT' AND active` — design.md D9),
      `bank_transactions`, `CREATE EXTENSION IF NOT EXISTS pg_trgm` + its GIN index on
      `merchant_key` (design.md "Database Schema Changes")
- [x] 1.2 Add `org.apache.commons:commons-csv` to `backend/pom.xml` (no CSV dependency exists yet)

## 2. Backend — domain/repo

- [x] 2.1 `BankStatementImport`, `BankTransaction`, `MerchantRule`, `MerchantAlias` entities
- [x] 2.2 `BankStatementImportRepository`, `BankTransactionRepository` (finders: by import id
      grouped by status; by fingerprint for duplicate lookup; by
      `linked_expense_entry_id IS NOT NULL AND import_id = ?` for revert), `MerchantRuleRepository`
      (finders: by normalized_merchant + rule_type + active; the plain-merchant-default lookup),
      `MerchantAliasRepository`

## 3. Backend — parsing + normalization + rule engine

- [x] 3.1 `CsvStatementParser`: header-based column mapping (Date/Description/Amount, with a
      Debit/Credit-column fallback — design.md §14), loud failure on unrecognized headers, per-row
      fingerprint + `occurrence_index` computation (design.md D7)
- [x] 3.2 `MerchantNormalizer`: the 6-step deterministic pipeline (design.md D2) — uppercase/trim,
      bank-descriptor-noise regex strip, punctuation cleanup, `merchant_aliases` lookup,
      `normalized_merchant`, `merchant_key`
- [x] 3.3 `MerchantRuleEngine`: the 5-tier evaluation (Fingerprint → Merchant+Keyword →
      Merchant+AmountRange → plain Merchant → fuzzy `pg_trgm` similarity) with confidence scoring
      and `match_reason` string generation per design.md §16; reference-number-only descriptions
      (checks, bare ACH/wire traces) skip straight to manual review

## 4. Backend — services

- [x] 4.1 `ExpenseImportService.importStatement(MultipartFile)`: parse, normalize, dedupe against
      existing `bank_transactions` by fingerprint, run the rule engine per row, persist the import
      + all transactions
- [x] 4.2 `ExpenseImportService.completeReconciliation(importId)`: for every non-excluded,
      non-duplicate, categorized transaction, call the existing
      `ExpenseService.createExpenseEntry(...)` unchanged (design.md D3), store
      `linked_expense_entry_id`, mark import `COMPLETED`
- [x] 4.3 `ExpenseImportService.revertImport(importId)`: delete every `expense_entries` row the
      import created via the existing `ExpenseService.deleteExpenseEntry`, reset transactions to
      `UNMATCHED`, mark import `REVERTED` (design.md D10)
- [x] 4.4 `MerchantRuleService`: create/update/delete rules; the "remember this for {merchant}"
      mutation path triggered from a transaction category change, including the plain-merchant
      conflict detection + before/after messaging (design.md D6/D9)
- [x] 4.5 `MerchantRuleService`: reinforcement on confirm-without-change (`times_applied`/
      `last_applied_at` increment, no new rule)
- [x] 4.6 `PayrollDisbursementDetector`: description-pattern match against known manager
      (`AppUserRepository`, role MANAGER) and provider payee names, suggesting
      `EXCLUDED / PAYROLL`; unrecognized payees fall through to Unknown/Needs Review, never
      force-excluded (design.md D11)
- [x] 4.7 `OwnerOverviewService.expenseTotalForMonth`/`managerLaborCostForMonth`: for a month with a
      `COMPLETED` `bank_statement_imports` row overlapping it, source exclusively from that
      reconciliation's linked `expense_entries` rows and skip `ManagerTimeService`'s auto-computed
      figure for that month; months without statement coverage are unchanged (design.md D11)

## 5. Backend — controllers

- [x] 5.1 `ExpenseImportController` (`/api/owner/expenses/imports/**`) — all 7 routes in design.md
      §13's API table; relies on the existing `/api/owner/**` OWNER-only catch-all in
      `SecurityConfig`, no security-config change needed (matches `ExpenseController`'s own
      precedent)
- [x] 5.2 `MerchantRuleController` (`/api/owner/expenses/rules/**`) — list/edit/delete

## 6. Backend — tests

- [x] 6.1 `MerchantNormalizerTest`: the exact `SQ *AKLUXNAILS`/`SQ AKLUXNAILS`/`SQ* AKLUX NAILS`/
      `Square AKLUXNAILS` equivalence set, plus prefix/suffix-stripping cases
- [x] 6.2 Fingerprint tests: identical inputs match, any differing input doesn't, same-day true
      repeats get distinct `occurrence_index`-qualified fingerprints, a byte-identical re-import
      round-trips to full duplicate detection
- [x] 6.3 `MerchantRuleEngineTest`: one scenario per tier in isolation, keyword-beats-plain-merchant
      and amount-range-beats-plain-merchant precedence, fuzzy tier never exceeds 0.75 confidence,
      reference-number-only inputs skip straight to manual review, no-rule → Unknown
- [x] 6.4 `ExpenseImportServiceTest`: parse-and-persist fixture round-trip; duplicate detection
      against a pre-existing fixture; `completeReconciliation` creates exactly one
      `expense_entries` row per eligible transaction with the correct sign; `revertImport` deletes
      only the rows it created and leaves unrelated `expense_entries` rows untouched
      (manually-entered ones, other imports')
- [x] 6.5 `ExpenseImportControllerTest` / `MerchantRuleControllerTest`: OWNER-only gating
      (mirrors `ExpenseControllerTest`'s existing pattern); malformed CSV → clear 4xx not 500
- [x] 6.6 `PayrollDisbursementDetectorTest`: recognized manager/provider payee descriptors suggest
      `EXCLUDED / PAYROLL`; an unrecognized payee falls through to Unknown, never force-excluded
      (design.md D11)
- [x] 6.7 `OwnerOverviewServiceTest`: a month with a `COMPLETED` import sources
      expenseTotal/managerLaborCost exclusively from that reconciliation and does not add
      `ManagerTimeService`'s auto-computed figure on top; a month with no import behaves exactly as
      before this change (design.md D11)

## 7. Frontend — upload + entry point

- [x] 7.1 `frontend/app/owner/overview/expenses/page.tsx`: add an "Import Statement" entry point
      alongside the existing `ExpenseEntryForm`
- [x] 7.1a `ExpenseEntryForm.tsx`: warn (not block) when the selected period already has a
      `COMPLETED` statement reconciliation — "already reconciled from an imported statement, entering
      this here risks double-counting" (design.md D11)
- [x] 7.2 `frontend/app/owner/overview/expenses/import/page.tsx` + `StatementUploadForm.tsx`:
      file picker/drag-drop, upload, redirect to the new import's reconciliation screen on success

## 8. Frontend — reconciliation workspace

- [x] 8.1 `frontend/app/owner/overview/expenses/import/[importId]/page.tsx` (server component,
      OWNER-gated same as `expenses/page.tsx`) + `ReconciliationWorkspace.tsx` (client — owns
      filter/search/selection state)
- [x] 8.2 `ImportSummaryHeader.tsx`, `TransactionFilterBar.tsx`, `TransactionSection.tsx` (collapsible,
      Needs Review expanded by default, others collapsed — design.md §8)
- [x] 8.3 `TransactionRow.tsx`: dual-render mobile card / desktop table row, matching
      `ContactsTable`'s `sm:hidden` / `hidden sm:block` responsive convention
- [x] 8.4 `ConfidenceBadge.tsx` (✅/⚠/❌, reusing the existing pill styling conventions from
      `ContactInfoPanel`), `MatchReasonTip.tsx` (wraps the existing `InfoTip`), `CategorySelect.tsx`
      (existing `ExpenseCategory` values + an Exclude option with reason sub-select)
- [x] 8.5 `BulkActionBar.tsx`: reuses `PrepaidManager`'s `Set`-based selection + live count pattern
- [x] 8.6 "Remember this for {merchant}" checkbox + conflict before/after messaging on any category
      change (design.md D6/D9)
- [x] 8.7 "Complete Reconciliation" action + confirmation of what will be written

## 9. Frontend — history + rules screens

- [x] 9.1 `frontend/app/owner/overview/expenses/history/page.tsx`: list, reopen, revert, download
      original file
- [x] 9.2 `frontend/app/owner/overview/expenses/rules/page.tsx` + `MerchantRulesTable.tsx`:
      list/edit/delete/deactivate any learned rule directly

## 10. Frontend — types + api client

- [x] 10.1 `frontend/app/lib/types.ts`: `BankStatementImport`, `BankTransaction`, `MerchantRule`
      types (mirroring the backend DTOs exactly, same convention as the existing `ExpenseEntry`
      type)
- [x] 10.2 `frontend/app/lib/api.ts` + proxy routes under `frontend/app/api/owner/expenses/...`
      for all endpoints in design.md §13

## 11. Verification

- [x] 11.1 `mvn test` clean (incl. jacoco coverage gate — see `docs/COVERAGE.md`); `tsc`/`eslint`/
      `next build` clean
- [ ] 11.2 Real click-through check with a small real (or realistic fixture) CSV: upload → confirm
      grouping/counts on the review screen → correct a transaction and confirm the "remember"
      checkbox creates a rule → re-upload the same file and confirm full duplicate detection →
      complete reconciliation and confirm the new `expense_entries` rows show up on the Net tab →
      revert and confirm they disappear again (clean up any test data per this session's standing
      rule)
- [ ] 11.3 Owner review of category-list assumptions (no new category invented, §"Non-goals"), the
      exclude-reason list, and the manager/provider payee-name list `PayrollDisbursementDetector`
      will match against (design.md D11), before this ships to production data
- [x] 11.4 Push to a new branch, open a PR, wait for CI, ask for explicit merge/deploy confirmation
      — same as every prior change this session
