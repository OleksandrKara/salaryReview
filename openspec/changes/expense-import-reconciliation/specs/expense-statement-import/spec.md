## ADDED Requirements

### Requirement: A CSV bank statement can be uploaded, parsed, and fingerprinted
The system SHALL accept a single CSV file upload (multipart), parse each row into a transaction
using the configured column mapping (Date/Description/Amount, or a Debit/Credit-column fallback),
and compute a fingerprint per row from its date, amount, and normalized merchant, so that identical
transactions can be recognized across separate imports.

#### Scenario: A well-formed statement parses successfully
- **WHEN** the owner uploads a CSV whose header row matches the configured column mapping
- **THEN** the system creates a `bank_statement_imports` row and one `bank_transactions` row per
  data row, each with a computed `fingerprint`, `normalized_merchant`, and `merchant_key`

#### Scenario: An unrecognized file format fails loudly
- **WHEN** the owner uploads a CSV whose header row does not match any recognized column mapping
- **THEN** the upload is rejected with a clear error identifying the missing/unexpected columns,
  and no `bank_statement_imports` or `bank_transactions` rows are created

### Requirement: True same-day repeat transactions are distinguished from accidental duplicates
The system SHALL assign each transaction an `occurrence_index` equal to the count of prior rows
within the same import sharing an identical (date, amount, normalized merchant) tuple, so that two
genuinely repeated charges on the same day are not conflated with a single row appearing twice due
to an export artifact.

#### Scenario: Two genuinely identical charges the same day both persist
- **WHEN** an import contains two rows with the same date, amount, and merchant (e.g. two $9.99
  charges from the same subscription service on the same day)
- **THEN** both rows are persisted as separate `bank_transactions`, with `occurrence_index` 0 and 1
  respectively, and neither is marked `DUPLICATE` of the other within that import

### Requirement: Re-importing an already-seen transaction is detected and never double-counted
The system SHALL, for each transaction being imported, check whether a transaction with the same
(fingerprint, occurrence_index) already exists among previously imported (non-reverted)
transactions; if so, the new row SHALL be marked `DUPLICATE` and linked to the original, and SHALL
never produce a second `expense_entries` row.

#### Scenario: The exact same statement file is uploaded twice
- **WHEN** a CSV is uploaded whose every row's (fingerprint, occurrence_index) already exists from
  a prior, non-reverted import
- **THEN** every row in the new import is marked `DUPLICATE`, and completing this import creates
  zero new `expense_entries` rows

#### Scenario: An overlapping-date statement is uploaded
- **WHEN** a newly uploaded statement's date range partially overlaps a prior import, and some of
  its rows match existing (fingerprint, occurrence_index) pairs while others don't
- **THEN** only the matching rows are marked `DUPLICATE`; the non-matching rows are processed
  normally (matched, reviewed, or excluded per the merchant-rule-engine and
  expense-reconciliation-workspace capabilities)

### Requirement: The original uploaded file is retained and re-downloadable
The system SHALL store the raw bytes of every uploaded statement file, retrievable by an OWNER at
any later time, independent of whether the import has been completed or reverted.

#### Scenario: Downloading a past import's original file
- **WHEN** an OWNER requests the original file for a past `bank_statement_imports` row
- **THEN** the exact bytes originally uploaded are returned, regardless of the import's current
  status

### Requirement: A completed import can be reverted, undoing only its own financial effect
The system SHALL, on reverting a `COMPLETED` import, delete every `expense_entries` row that
import's transactions created, reset those transactions to `UNMATCHED` (preserving their match
history and any learned rules), and mark the import `REVERTED` — leaving every other import's and
every manually-entered `expense_entries` row untouched.

#### Scenario: Reverting an import removes only its own expense entries
- **WHEN** an OWNER reverts a `COMPLETED` import that created 40 `expense_entries` rows
- **THEN** exactly those 40 rows are deleted, the import's 40 transactions return to `UNMATCHED`
  with `linked_expense_entry_id` cleared, the import's status becomes `REVERTED`, and any
  `expense_entries` rows from other imports or manual entry are unaffected

#### Scenario: A reverted import can be re-completed after correction
- **WHEN** an OWNER re-reconciles and re-completes a previously `REVERTED` import
- **THEN** the system creates a fresh set of `expense_entries` rows for the (possibly corrected)
  categorization, exactly as it would for any other completion
