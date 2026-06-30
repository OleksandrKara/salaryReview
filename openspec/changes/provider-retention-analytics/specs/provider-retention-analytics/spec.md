## ADDED Requirements

### Requirement: Persistent customer-visit ledger

The system SHALL persist a visit ledger — one row per `(customer, provider, service_date)`, with a same-day-rebook flag and a service category — populated from the month aggregator's attributed services. Ingest SHALL be idempotent on the natural key and SHALL support both daily accrual and a one-time historical backfill.

#### Scenario: Daily accrual records each visit once
- **WHEN** the daily ingest runs for a date with attributed services
- **THEN** one visit row exists per `(customer, provider, date)`, and re-running the same date adds no duplicates

#### Scenario: Backfill matches accrual
- **WHEN** a past month is backfilled
- **THEN** it produces the same visit rows the daily accrual would have, with no duplicates against existing rows

#### Scenario: Anonymous visits are isolated
- **WHEN** a service has no Square `customerId`
- **THEN** it is recorded as anonymous and excluded from new/returning and retention rates (reported separately, not as churn)

### Requirement: New vs returning, per provider per month

The system SHALL report, per provider per month, the count of clients seen, clients new to that provider, clients returning to that provider, and clients new to the salon whose first salon visit was with that provider. "New" classification SHALL be derived from the ledger at query time (first-visit detection), not baked in at write time.

#### Scenario: First-ever pairing counts as new-to-provider
- **WHEN** a customer's earliest visit with provider P (in the ledger) falls in month M
- **THEN** that customer is counted as new-to-provider for P in M; otherwise returning-to-provider

#### Scenario: Fresh salon client acquired by a provider
- **WHEN** a customer's first-ever salon visit (any provider) falls in month M and was with provider P
- **THEN** that customer is counted as new-to-salon-via-P for M

### Requirement: Cohort retention with maturity

The system SHALL compute, for each monthly cohort of a provider's new clients, the share who returned within a window K — both to the same provider (provider retention) and to the salon (salon retention). A cohort SHALL be marked **immature** until K has elapsed, and immature cohorts SHALL NOT be presented as a low/zero retention result.

#### Scenario: Matured cohort reports a rate
- **WHEN** at least K days have elapsed since a cohort's reference point
- **THEN** the cohort shows provider- and salon-retention percentages

#### Scenario: In-flight cohort is not judged
- **WHEN** fewer than K days have elapsed
- **THEN** the cohort is shown as "too soon" rather than a 0%/low rate

### Requirement: Same-day rebook rate

The system SHALL compute, per provider per month, the share of visits where the customer had a future-dated booking created on the same calendar day (salon timezone) as the visit. This requires Square's booking `created_at`.

#### Scenario: Rebooked-before-leaving is detected
- **WHEN** a customer with a visit on day D also has a future booking whose `created_at` is on day D
- **THEN** that visit counts toward the provider's same-day rebook rate for that month

### Requirement: Trend and acquisition-leak risk

The system SHALL present month-over-month trend (clientele size and new/returning mix) per provider, and SHALL flag an acquisition-leak risk when a provider receives many new-to-salon clients whose retention is below a threshold.

#### Scenario: Leak flag
- **WHEN** a provider's new-to-salon-via-P count is high but those clients' (matured) retention is below the configured threshold
- **THEN** the provider is flagged as an acquisition-leak risk

### Requirement: Owner-only access

The retention analytics view and its endpoints SHALL be owner-only; managers and providers SHALL be denied. The view SHALL be usable on mobile and web.

#### Scenario: Non-owners are blocked
- **WHEN** a manager or provider requests the retention analytics page or API
- **THEN** access is denied (redirected / 403), consistent with other owner-only surfaces
