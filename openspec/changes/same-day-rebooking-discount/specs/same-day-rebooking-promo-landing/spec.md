## ADDED Requirements

### Requirement: The promo query parameters are signature-verified server-side before being trusted
The homepage SHALL recompute the expected HMAC signature over the `promo` and `exp` query
parameters and compare it to the supplied `sig` parameter, server-side, before treating the visit
as carrying an active promo. A visit with a missing or mismatched signature SHALL be treated
identically to a visit with no promo parameters at all — no banner, no promo state threaded into
the booking flow.

#### Scenario: Valid signature is honored
- **WHEN** a visitor loads the homepage with `promo`, `exp`, and a `sig` that correctly matches
  those values under the shared signing secret
- **THEN** the visit is treated as carrying an active promo (subject to the `exp` expiry check
  below)

#### Scenario: Tampered or missing signature is rejected
- **WHEN** a visitor loads the homepage with `promo`/`exp` present but a `sig` that does not match
  (edited `exp`, missing `sig`, or any other mismatch)
- **THEN** the visit is treated exactly as if no promo parameters were present — no banner is
  shown and no promo state is available to the booking flow

### Requirement: The promo banner appears only for a tracked, verified visit, and is mobile-first
The homepage SHALL show a promo banner only when `promo`, `exp`, and a matching `sig` are all
present and verified on the visit URL. A normal, untracked homepage visit, or one with an invalid
signature, SHALL show no banner. The banner SHALL be laid out full-width and legible on small
mobile viewports, not a desktop-only or easily-missed element.

#### Scenario: Untracked visit shows no banner
- **WHEN** a visitor loads the homepage with no `promo`/`exp` query parameters
- **THEN** no promo banner is rendered

#### Scenario: Tracked visit on mobile shows a full-width, legible banner
- **WHEN** a visitor loads the homepage with `promo=REBOOK10&exp=<future epoch>` on a mobile-width
  viewport
- **THEN** the banner renders full-width, with the discount and countdown both legible without
  horizontal scrolling or a magnified pinch-zoom

### Requirement: The banner shows a live countdown while the offer is unexpired, and an expired state otherwise
While `exp` is in the future, the banner SHALL display a live, client-side ticking countdown to
that moment. Once `exp` has passed, the same banner slot SHALL instead show an explicit
"offer expired" state.

#### Scenario: Unexpired promo shows a live countdown
- **WHEN** a visitor loads the homepage with `promo=REBOOK10&exp=<future epoch>`
- **THEN** the banner shows the $10 discount and a countdown that ticks down toward that epoch
  without requiring a page reload

#### Scenario: Expired promo shows an explicit expired state
- **WHEN** a visitor loads the homepage with `promo=REBOOK10&exp=<past epoch>`
- **THEN** the banner shows that the offer has expired, not a live countdown and not silence

### Requirement: An active, unexpired, $99+ promo is reflected in the booking flow's price display
While a visitor's session carries an active (unexpired, signature-verified) promo and the current
booking subtotal is at least $99, the booking summary SHALL display the $10 discount as a
subtracted line against the booking subtotal before the customer confirms. Below $99, no discount
line is shown even if the promo is otherwise active. This is a display-only estimate; no payment
is processed by this booking flow with or without an active promo.

#### Scenario: Booking summary shows the discount once the $99 minimum is met
- **WHEN** a visitor arrived via a verified, unexpired `promo=REBOOK10` link and their selected
  services total at least $99
- **THEN** the displayed total includes a $10 deduction line, distinct from the underlying
  service subtotal

#### Scenario: Cart below $99 shows no discount line
- **WHEN** a visitor has an active, unexpired, verified promo but their selected services total
  less than $99
- **THEN** the booking summary shows no discount line until the subtotal reaches $99

#### Scenario: No promo present, no discount line shown
- **WHEN** a visitor books with no verified promo ever present in their session
- **THEN** the booking summary shows no discount line, unchanged from today's behavior

### Requirement: A booking completed under an active promo enrolls the customer in an automatic, Square-enforced discount
When a booking is created while a valid, unexpired, signature-verified promo is active, the system
SHALL add the customer to a dedicated Square customer group for the remainder of their personal
offer window, such that a pre-configured Square `CatalogPricingRule` automatically applies the
$10 discount to any qualifying ($99+ subtotal) Order Square generates for that customer during
that window — no staff action required to apply the discount itself. The created Square Booking's
`sellerNote` SHALL additionally name the automatic discount, the offer's cutoff, and explicitly
warn staff not to also apply the existing manual "Same day rebooking discount" (which would stack
to $20 off), and a staff Telegram alert SHALL carry the same warning. Group membership SHALL be
removed once the customer's personal offer window expires.

#### Scenario: Promo-flagged booking enrolls the customer for automatic discounting
- **WHEN** a customer completes a booking while an unexpired, verified promo is active in their
  session
- **THEN** that customer is added to the dedicated Square customer group backing the automatic
  discount, the created Square Booking's `sellerNote` warns staff not to also apply the manual
  discount, and a Telegram alert is sent to staff naming the customer, appointment time, and the
  same warning

#### Scenario: Group membership is removed once the personal offer window expires
- **WHEN** a customer's personal `promo_expires_at` (midnight `America/Los_Angeles` on the day
  they paid) passes
- **THEN** that customer is removed from the dedicated Square customer group, so no further Order
  for them automatically receives the discount

#### Scenario: Ordinary booking is unaffected
- **WHEN** a customer completes a booking with no verified promo ever active in their session
- **THEN** the created Square Booking's `sellerNote` is unchanged from today's behavior, no group
  enrollment happens, and no extra Telegram alert is sent
