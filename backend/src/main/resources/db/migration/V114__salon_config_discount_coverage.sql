-- Per-business control over which Square order discounts the salon "absorbs" (pays the provider
-- commission on the full pre-discount price) versus which reduce the provider's commission basis
-- down to what was actually collected. Defaults to false/null everywhere — every existing business
-- keeps its current, historical behavior (cover every discount) unless explicitly reconfigured via
-- the Business Settings form. Deliberately named/defaulted so an unstubbed boolean (Java's own
-- primitive default, and Mockito's default for an unstubbed mock method) means "off" = legacy
-- behavior, not the new restricted one — a mock that forgets to stub this still behaves like today.
-- covered_discount_names is only consulted when restrict_discount_coverage is true: a comma-
-- separated list of case-insensitive substrings matched against each Square discount's own name
-- (same free-text-list convention as salon_config's existing invoice_ref style fields).
ALTER TABLE salon_config
    ADD COLUMN restrict_discount_coverage BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN covered_discount_names VARCHAR(500);
