## ADDED Requirements

### Requirement: Transactions are grouped by status with review-worthy items surfaced first
The system SHALL present an import's transactions grouped into Needs Review, Automatically
Categorized, Excluded, and Duplicates, with Needs Review expanded by default and the other groups
collapsed by default but fully visible on expansion.

#### Scenario: A fresh import opens with attention on what needs it
- **WHEN** the owner opens a newly imported, not-yet-completed reconciliation
- **THEN** the Needs Review section is shown expanded, and Automatically Categorized, Excluded, and
  Duplicates sections are shown collapsed with their counts visible in the section header

### Requirement: Every automatic categorization is accompanied by a visible explanation
The system SHALL display, for every automatically categorized or suggested transaction, a
human-readable reason describing which rule or similarity match produced that result.

#### Scenario: The owner can see why a transaction was auto-categorized
- **WHEN** the owner views an automatically categorized transaction
- **THEN** a visible affordance (tap/click) reveals the specific matched rule and its type (e.g.
  "Matched because: Normalized Merchant = COSTCO")

### Requirement: Confidence is visually distinguished into exactly three states
The system SHALL display each transaction's status as one of exactly three visual states:
Automatically Categorized, Needs Review, or Unknown, using distinct, consistent indicators across
the whole workspace.

#### Scenario: A high-confidence match is marked distinctly from a low-confidence one
- **WHEN** the owner views a list containing both an auto-matched transaction (confidence ≥ 0.75)
  and a low-confidence or fuzzy-suggested transaction (confidence < 0.75)
- **THEN** the two are rendered with visually distinct indicators, and the low-confidence one is
  placed in the Needs Review group regardless of whether a category was suggested for it

### Requirement: Multiple transactions can be categorized in a single action
The system SHALL allow the owner to select multiple transactions and assign one category (or
exclude reason) to all of them in a single action.

#### Scenario: Bulk-categorizing a selection of transactions
- **WHEN** the owner selects several Needs Review transactions and chooses a category via a bulk
  action
- **THEN** all selected transactions are set to that category in one action, and the "remember for
  this merchant" option is offered only when every selected transaction shares the same merchant

### Requirement: Completing a reconciliation writes an ordinary expense entry per reconciled transaction
The system SHALL, upon completing a reconciliation, create exactly one existing-format
`expense_entries` row for every transaction that is categorized and neither excluded nor a
duplicate, using that transaction's own date as both the entry's period start and end and its
absolute amount, and SHALL create no such row for any excluded or duplicate transaction.

#### Scenario: Completing writes entries only for reconciled, non-excluded, non-duplicate rows
- **WHEN** an import with 50 transactions — 40 categorized, 6 excluded, 4 duplicate — is completed
- **THEN** exactly 40 new `expense_entries` rows are created, none for the excluded or duplicate
  transactions, and each new row's amount and category reflect its source transaction

#### Scenario: Excluded transactions never affect Net Revenue
- **WHEN** a transaction is marked Excluded (for any reason: transfer, credit card payment,
  payroll, tax, owner contribution, cash withdrawal, or other) and the import is completed
- **THEN** no `expense_entries` row is created for that transaction, and the salon's computed Net
  Revenue for that period is unaffected by its amount

### Requirement: A statement-covered month sources its expenses exclusively from that reconciliation
The system SHALL, for any calendar month with at least one `COMPLETED` statement import overlapping
it, resolve that month's expense total and manager labor cost exclusively from that reconciliation's
linked `expense_entries` rows, without also adding a separately computed or manually entered total
for the same month. A month with no completed statement import SHALL be unaffected and continue to
use the existing computed/manual sourcing.

#### Scenario: A statement-covered month excludes the separately computed manager labor cost
- **WHEN** a calendar month has a `COMPLETED` statement import, and that month's reconciled
  transactions include manager-time-categorized expense entries
- **THEN** the month's manager labor cost is the sum of those reconciled expense entries only, and
  the auto-computed clocked-hours total for that same month is not additionally subtracted

#### Scenario: A month without any statement import is unaffected
- **WHEN** a calendar month has no statement import at all
- **THEN** that month's expense total and manager labor cost are resolved exactly as they were
  before this feature existed

#### Scenario: Manual entry for an already-covered month is flagged, not silently accepted
- **WHEN** the owner submits a manual expense entry whose period falls within a month that already
  has a `COMPLETED` statement import
- **THEN** the owner sees a warning that this month is already reconciled from an imported statement
  and that entering it here risks double-counting, before the entry is saved

### Requirement: The reconciliation workspace is fully usable on a phone without horizontal scrolling
The system SHALL render the transaction list as touch-friendly cards (not a wide table) on small
viewports, with category assignment reachable in a single tap and no required horizontal scroll.

#### Scenario: Reviewing and categorizing a transaction on a mobile viewport
- **WHEN** the reconciliation workspace is viewed on a mobile-width screen
- **THEN** transactions render as stacked cards with no horizontal scrollbar, and assigning a
  category to a transaction requires no more than one tap to open the category chooser
