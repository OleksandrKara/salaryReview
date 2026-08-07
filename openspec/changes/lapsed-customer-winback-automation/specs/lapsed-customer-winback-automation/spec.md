## ADDED Requirements

### Requirement: A customer whose only visit was 21–35 days ago and who hasn't rebooked gets a win-back text with a $5 coupon link
The system SHALL scan `provider_visit` once daily for customers with exactly one all-time visit
whose `service_date` falls 21–35 days before the current date, and — for each one whose phone
number resolves to no upcoming Square appointment and who has never left negative feedback — send
one win-back text carrying a click-tracked $5-coupon link valid until the end of the day it's sent,
provided the `lapsed_customer_winback` automation is enabled and this customer has never been
processed by this automation before.

#### Scenario: Eligible lapsed customer gets the win-back text
- **WHEN** a customer has exactly one row in `provider_visit`, that row's `service_date` is between
  21 and 35 days before today, their phone number resolves to no Square customer with a
  not-cancelled booking whose `startAt` is still in the future, they have never left negative
  feedback, `lapsed_customer_winback` is enabled, and they have no existing
  `lapsed_customer_winback_send` row
- **THEN** the win-back SMS is sent to that customer's phone number and a
  `lapsed_customer_winback_send` row is written with `state = SENT`

#### Scenario: Customer with 2 or more visits is never targeted
- **WHEN** a customer has more than one row in `provider_visit`, regardless of how long ago the most
  recent one was
- **THEN** this automation never considers them — the eligibility query excludes any customer whose
  visit count is not exactly 1

#### Scenario: Visit outside the 21–35 day window is not yet (or no longer) eligible
- **WHEN** a customer's single visit is fewer than 21 days ago or more than 35 days ago
- **THEN** no SMS is sent and no `lapsed_customer_winback_send` row is written for that customer on
  this run

#### Scenario: Customer already rebooked on their own is skipped
- **WHEN** a customer's single visit is 21–35 days ago, but their phone number resolves to a Square
  customer with a booking that hasn't happened yet and hasn't been cancelled, declined, or marked
  no-show — regardless of which channel that booking was made through
- **THEN** no SMS is sent, and a `lapsed_customer_winback_send` row is written with
  `state = SKIPPED_BOOKED`

#### Scenario: Customer who left negative feedback is never re-approached
- **WHEN** a customer is otherwise eligible but has ever left negative feedback on any prior
  automation (`sms_message.negative_feedback_at IS NOT NULL` for their phone number)
- **THEN** no SMS is sent, and a `lapsed_customer_winback_send` row is written with
  `state = SKIPPED_NEGATIVE_FEEDBACK`

#### Scenario: Automation disabled
- **WHEN** a customer is otherwise eligible but `lapsed_customer_winback` is disabled
- **THEN** no SMS is sent, and a `lapsed_customer_winback_send` row is written with
  `state = SKIPPED_DISABLED`

#### Scenario: Phone number cannot be resolved
- **WHEN** a customer is otherwise eligible but no phone number can be resolved for their Square
  customer id
- **THEN** no SMS is sent, and a `lapsed_customer_winback_send` row is written with
  `state = SKIPPED_UNRESOLVED`

#### Scenario: A customer is never processed twice
- **WHEN** a customer already has a `lapsed_customer_winback_send` row, in any state
- **THEN** a later daily run does not re-evaluate or re-send for that customer, even if they later
  meet the "exactly one visit, 21–35 days ago" criteria again under a different visit

### Requirement: Message wording is consent-branched; the coupon applies on click either way
The system SHALL send one of two message variants depending on SMS-marketing consent, both carrying
the same click-tracked coupon link — the coupon applies when clicked and booked before expiry
regardless of which variant was sent, so only the consented variant's *wording* names the $5
discount outright.

#### Scenario: Consented customer gets the marketing variant naming the $5 coupon
- **WHEN** the `lapsed_customer_winback` automation sends to a customer who has SMS-marketing
  consent (via `marketing.contacts.sms_marketing_consent` or Square's own consent-segment
  membership — the same dual-source check `same_day_rebooking_discount` already uses)
- **THEN** the message explicitly mentions the $5 coupon and its same-day expiry

#### Scenario: Non-consented customer gets the transactional variant with no discount language
- **WHEN** the `lapsed_customer_winback` automation sends to a customer with no SMS-marketing
  consent on file
- **THEN** the message contains no discount, coupon, or promotional language — just a plain link
  back to book — and the send is not blocked on consent grounds, since this variant is classified
  transactional
- **AND** the same coupon link is included and the discount still applies if the customer clicks and
  books before it expires

#### Scenario: Coupon expires at the end of the send day, not the visit day
- **WHEN** the automation sends a win-back message to an eligible customer
- **THEN** the coupon link's expiry (`lapsed_customer_winback_send.promo_expires_at`) is set to the
  start of the next calendar day in the salon's local timezone (`America/Los_Angeles`), measured
  from the day the message is sent — not from the customer's original visit date, which is 21–35
  days earlier

### Requirement: New automation ships disabled by default
The `lapsed_customer_winback` automation's `sms_automation` row SHALL default to `enabled = false`
when seeded by its migration.

#### Scenario: Fresh deployment has the automation off
- **WHEN** the migration that seeds `lapsed_customer_winback` runs
- **THEN** `SELECT enabled FROM sms_automation WHERE automation_key = 'lapsed_customer_winback'`
  returns `false` until an OWNER explicitly turns it on
