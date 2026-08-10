-- Bank statement CSVs from this business's bank carry a leading "Account Summary" block with the
-- account's own printed Beginning/Ending balance ("Beginning balance as of 07/01/2026", etc.) —
-- CsvStatementParser has always parsed straight past it as a non-transaction row and discarded
-- the values. Capturing them here enables a real bank-account reconciliation check (opening +
-- imported transactions = closing) that didn't exist before. Nullable, no backfill — only newly
-- uploaded statements populate these; older imports simply have no reconciliation check shown.
ALTER TABLE bank_statement_imports ADD COLUMN opening_balance NUMERIC(12,2);
ALTER TABLE bank_statement_imports ADD COLUMN closing_balance NUMERIC(12,2);
