## ADDED Requirements

### Requirement: Merchant descriptors are normalized to a single canonical form
The system SHALL reduce a raw transaction description to a canonical `normalized_merchant` by
uppercasing, stripping known bank-specific prefixes/suffixes, removing punctuation noise, and
applying any learned merchant alias, so that superficially different descriptors for the same real
merchant match identically.

#### Scenario: Common Square descriptor variants resolve to the same merchant
- **WHEN** normalizing the raw descriptions `SQ *AKLUXNAILS`, `SQ AKLUXNAILS`, `SQ* AKLUX NAILS`,
  and `Square AKLUXNAILS`
- **THEN** all four produce the identical `normalized_merchant` value

#### Scenario: A confirmed merchant alias is applied on every future import
- **WHEN** the owner confirms that a new descriptor variant refers to an existing canonical
  merchant (creating a `merchant_aliases` row)
- **THEN** every future transaction with that raw descriptor normalizes to the existing canonical
  merchant, not a new one

### Requirement: Categorization is attempted through a deterministic, priority-ordered rule evaluation
The system SHALL evaluate, in order, whether an active fingerprint rule, merchant+keyword rule,
merchant+amount-range rule, or plain merchant-level rule matches a transaction, applying the first
match found; if none match, it SHALL attempt a fuzzy merchant-similarity suggestion; if that also
fails, the transaction SHALL be marked `Unknown` and routed to manual review. Every automatic match
SHALL record a human-readable reason.

#### Scenario: A fixed-amount recurring charge matches by fingerprint
- **WHEN** a transaction's merchant, amount, and description match an existing fingerprint rule
  created from a previously reconciled transaction
- **THEN** the transaction is categorized per that rule with confidence 0.99 and a match reason
  citing the fingerprint match

#### Scenario: A keyword rule takes precedence over a plain merchant rule for the same merchant
- **WHEN** a transaction's merchant has both a plain merchant-level rule and a merchant+keyword
  rule, and the transaction's description contains that keyword
- **THEN** the merchant+keyword rule is applied, not the plain merchant-level rule

#### Scenario: An amount-range rule takes precedence when the amount is in range
- **WHEN** a transaction's merchant has both a plain merchant-level rule and a merchant+amount-range
  rule, and the transaction's amount falls within that range
- **THEN** the merchant+amount-range rule is applied

#### Scenario: A merchant with unresolved ambiguity is routed to review, not guessed
- **WHEN** a transaction's merchant has one or more keyword/amount-range rules, none of which match
  this transaction, and a plain merchant-level rule also exists
- **THEN** the plain merchant-level rule's category is offered only as a low-confidence (0.60)
  suggestion, and the transaction is routed to Needs Review rather than auto-categorized

#### Scenario: A never-seen merchant with a similar known merchant is suggested, never auto-applied
- **WHEN** no rule matches a transaction's exact `normalized_merchant`, but its `merchant_key` has
  a trigram similarity of at least 0.6 to another merchant with an active rule
- **THEN** the transaction is marked Needs Review with a suggested category and a stated similarity
  percentage, and is never auto-categorized regardless of how high the similarity score is

#### Scenario: A reference-number-only description never builds or consults a rule
- **WHEN** a transaction's raw description reduces to a bare check number or reference/trace number
  with no discernible merchant name
- **THEN** the transaction skips all rule-tier evaluation and is routed directly to manual review,
  and no rule is created from it even if the owner later categorizes it

#### Scenario: A genuinely unknown merchant is marked Unknown
- **WHEN** no fingerprint, keyword, amount-range, plain-merchant, or fuzzy-similarity match is found
- **THEN** the transaction's confidence is null, its status is Needs Review, and it is labeled
  Unknown

### Requirement: At most one plain merchant-level rule may be active per merchant
The system SHALL enforce that a given `normalized_merchant` has at most one active rule of type
`MERCHANT`, so that a merchant with genuinely different categorizations in different contexts must
be disambiguated by a keyword or amount-range rule rather than silently overwritten.

#### Scenario: A second, conflicting plain-merchant categorization is detected as a conflict
- **WHEN** the owner categorizes a transaction for a merchant that already has an active plain
  merchant-level rule pointing to a different category, and chooses to "remember this"
- **THEN** the system does not silently create a second plain merchant-level rule; it surfaces the
  conflict (the existing category and the newly chosen one) and requires the owner to either
  replace the existing rule or add a keyword/amount-range condition to disambiguate

### Requirement: A category decision can be remembered as a rule, visibly and reversibly
The system SHALL, when the owner confirms or corrects a transaction's category, offer to save that
decision as a rule for future transactions from the same merchant, defaulting to on, and SHALL show
what will change before the rule is saved if it would alter existing future-categorization
behavior. Saved rules SHALL remain editable and deletable independent of any transaction.

#### Scenario: A first-time category choice creates a new rule
- **WHEN** the owner categorizes a transaction from a merchant with no existing rule, and leaves
  the "remember this" option checked
- **THEN** a new active `MERCHANT`-type rule is created for that merchant and category

#### Scenario: Declining to remember applies the decision to only one transaction
- **WHEN** the owner unchecks "remember this" before confirming a category
- **THEN** only that transaction's own category is set; no rule is created or modified

#### Scenario: Confirming an auto-categorized transaction without changes reinforces the rule
- **WHEN** the owner confirms a transaction whose category was already set by an automatic rule
  match, without changing it
- **THEN** the matched rule's `times_applied` count increments and `last_applied_at` updates; no
  new rule is created

#### Scenario: A learned rule can be edited or removed directly
- **WHEN** the owner opens the merchant rules management view
- **THEN** every active and inactive rule is listed with its merchant, type, category, and usage
  history, and can be edited or deleted independent of any specific transaction
