## ADDED Requirements

### Requirement: No-show visibility scoped to one month
The system SHALL surface salon no-shows that are otherwise excluded from the report, scoped to a single displayed month. A no-show whose $25 fee has been paid SHALL appear in the month the fee was **paid** (which may be later than the no-show itself); a no-show with no paid fee SHALL appear in its own calendar month. Each row SHALL show the provider, customer, salon-local no-show date/time, and fee status, and SHALL NOT count as a paid service. Owners and managers (identical capabilities) SHALL see all providers' no-shows for the month; each provider SHALL see only their own.

#### Scenario: Fee paid in the same month
- **WHEN** a customer no-shows and the $25 fee is paid within the same month
- **THEN** the no-show appears in that month for its provider, tagged "fee paid"

#### Scenario: Fee paid in a later month
- **WHEN** a customer no-shows in month X and the $25 fee is paid in a later month Y
- **THEN** the no-show appears in month Y (the payment month), not in month X
- **AND** the provider's $25 credit is applied in month Y

#### Scenario: No fee paid
- **WHEN** a customer no-shows and no fee has been paid (yet, or ever)
- **THEN** the no-show appears in its own calendar month, tagged "no fee collected", with no credit

#### Scenario: Provider sees only their own no-shows
- **WHEN** a provider opens their own page
- **THEN** they see only no-shows attributed to them for that month, read-only (no override controls)

#### Scenario: Cancelled/declined bookings are not no-shows
- **WHEN** a booking has status `CANCELLED_BY_CUSTOMER`, `CANCELLED_BY_SELLER`, or `DECLINED`
- **THEN** it is not shown as a no-show fee row

### Requirement: Automatic detection of a paid cancellation fee
The system SHALL detect a paid $25 no-show fee from Square, read-only. A paid cancellation fee is a completed Square charge carrying a "Cancelation Policy" line of $25 for the customer, paid the same day as or up to **2 months** after the no-show. Each such fee SHALL be paired to the **nearest preceding** `NO_SHOW` for that same customer within that 2-month window, and each fee SHALL be paired at most once.

#### Scenario: Fee paid up to two months later is matched
- **WHEN** a customer has a paid cancellation fee (a completed "Cancelation Policy" charge of $25) on or after one of their `NO_SHOW` bookings, within 2 months of it
- **THEN** that fee is paired to the nearest preceding `NO_SHOW` for the customer and that no-show is tagged "fee paid"

#### Scenario: Payment beyond two months is not auto-matched
- **WHEN** the only matching cancellation fee is paid more than 2 months after the no-show
- **THEN** it is not auto-paired (the no-show shows "no fee collected" until a manager confirms it)

#### Scenario: One fee does not pay two no-shows
- **WHEN** a customer has two `NO_SHOW` bookings but only one paid cancellation fee
- **THEN** only the no-show nearest before the fee is tagged "fee paid"; the other remains "no fee collected"

#### Scenario: Square is never written
- **WHEN** the system detects, matches, or compensates a no-show fee
- **THEN** it only reads from Square (Bookings, Orders, Invoices) and performs no Square write

### Requirement: Provider compensation for a paid fee
When a no-show fee is paid, the system SHALL credit the provider $25 as a synthetic settlement line on the `NOSHOW` channel, folded through the existing extra-line mechanism. The credit SHALL NOT count toward the 50/50 tier threshold and SHALL land in the period in which the fee was paid.

#### Scenario: Paid fee adds a $25 credit
- **WHEN** a no-show is tagged "fee paid" for a provider
- **THEN** the provider's settlement for the fee's paid period includes a +$25 `NOSHOW` line
- **AND** the provider's counted-service total and tier qualification are unchanged by it

#### Scenario: Credit lands in the paid period
- **WHEN** the no-show is in one period but its fee invoice is paid in a later period
- **THEN** the $25 credit is applied in the period the fee was paid, not the period of the no-show

#### Scenario: Detected fee credits automatically
- **WHEN** a fee is auto-detected as paid for a no-show
- **THEN** the provider is credited without any manager action

#### Scenario: Multi-provider no-show splits the fee
- **WHEN** a no-show booking has services for more than one provider and its $25 fee is paid
- **THEN** the $25 is split evenly across the distinct providers (e.g. two providers each receive $12.50)

### Requirement: Manager override
Owners and managers SHALL be able to override detection for the messy real-world cases: confirm a fee that was collected off-signal (cash, quick-sale, or a non-standard invoice) so the provider is still credited, and remove (un-do) an auto-detected credit. Providers SHALL NOT have override access.

#### Scenario: Manager credits an off-signal fee
- **WHEN** a manager marks a no-show's fee as collected even though no matching paid invoice was detected
- **THEN** the provider receives the $25 `NOSHOW` credit as if it had been detected

#### Scenario: Manager removes a credit
- **WHEN** a manager un-does a no-show fee credit
- **THEN** the $25 `NOSHOW` line no longer appears in the provider's settlement

#### Scenario: Override is restricted
- **WHEN** a user without the OWNER or MANAGER role calls a no-show fee override endpoint
- **THEN** the request is rejected
