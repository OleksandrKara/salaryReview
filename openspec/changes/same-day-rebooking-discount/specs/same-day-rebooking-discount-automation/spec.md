## ADDED Requirements

### Requirement: A same-day-rebooking discount text is sent 3 hours after an in-salon checkout
The system SHALL, upon a qualifying Square `payment.updated` event (COMPLETED, not booking-linked,
customer and phone resolved — the same qualification `checkout_review_request` uses), enqueue a
`same_day_rebooking_send` row with `send_due_at` 3 hours after the payment completed and
`promo_expires_at` set to midnight `America/Los_Angeles` on the calendar day the payment
completed. At `send_due_at`, if the `same_day_rebooking_discount` automation is enabled, the
customer has no upcoming appointment, the offer has not already expired, and consent is present in
at least one of the two recognized sources, the system SHALL send the discount text.

#### Scenario: Eligible customer receives the discount text on schedule
- **WHEN** a `same_day_rebooking_send` row reaches its `send_due_at`, `promo_expires_at` is still
  in the future, the customer has no upcoming not-cancelled appointment, consent is present in
  `marketing.contacts.sms_marketing_consent` or the customer's Square `segment_ids`, and the
  automation is enabled
- **THEN** the discount SMS is sent with a link embedding the promo code and `promo_expires_at`,
  and the row's state becomes `SENT`

#### Scenario: Offer would already be expired by send time
- **WHEN** a `same_day_rebooking_send` row reaches `send_due_at` and `promo_expires_at` is already
  in the past
- **THEN** no SMS is sent and the row's state becomes `SKIPPED_EXPIRED`

#### Scenario: Customer already has an upcoming appointment
- **WHEN** a `same_day_rebooking_send` row reaches `send_due_at` and the customer's Square
  customer id has any booking that hasn't happened yet and hasn't been cancelled, declined, or
  no-showed
- **THEN** no SMS is sent and the row's state becomes `SKIPPED_BOOKED`

#### Scenario: Automation disabled
- **WHEN** a `same_day_rebooking_send` row reaches `send_due_at` while `same_day_rebooking_discount`
  is disabled
- **THEN** no SMS is sent and the row's state becomes `SKIPPED_DISABLED`

### Requirement: Consent is satisfied by either salaryReview's own record or Square's own segment
The system SHALL treat a contact as consented for this MARKETING-class send if
`marketing.contacts.sms_marketing_consent = true` for that phone number, **or** the resolved
Square customer's `segment_ids` includes the configured "Text Subscribers" segment id — either
condition alone is sufficient.

#### Scenario: Consent present only in Square
- **WHEN** a contact's `marketing.contacts.sms_marketing_consent` is `false` or the contact has no
  `marketing.contacts` row at all, but the resolved Square customer's `segment_ids` contains the
  configured Text Subscribers segment id
- **THEN** the send is not blocked on consent grounds

#### Scenario: Consent present only in salaryReview
- **WHEN** `marketing.contacts.sms_marketing_consent = true` for the contact's phone number, and
  the Square customer is not in the Text Subscribers segment (or has no resolvable segments)
- **THEN** the send is not blocked on consent grounds

#### Scenario: No consent in either source
- **WHEN** neither condition above is true
- **THEN** no SMS is sent and the row's state becomes `SKIPPED_NO_CONSENT`

### Requirement: The promo link is click-tracked and cryptographically signed
The system SHALL embed the discount link as a click-tracked `/r/{token}` short link (per the
existing `sms-automations-hub` mechanism) that resolves to
`https://akluxnails.com/?promo=REBOOK10&exp=<epoch seconds>&sig=<HMAC signature>`, where `sig` is
computed from the promo code and `exp` using a shared secret, so that `exp` cannot be extended by
editing the URL.

#### Scenario: Short link resolves to the live, signed promo URL with the correct expiry
- **WHEN** a recipient follows the `/r/{token}` link from a same-day-rebooking discount text
- **THEN** they are redirected to `https://akluxnails.com/?promo=REBOOK10&exp=<epoch seconds
  matching that send's promo_expires_at>&sig=<a valid HMAC signature over that promo code and
  epoch>` and the underlying `sms_message` row's `clicked_at` is set on first click

#### Scenario: A tampered exp value fails signature verification downstream
- **WHEN** a visitor edits the `exp` query parameter to a later value after receiving the link
- **THEN** the recomputed signature on akluxnails-home's side no longer matches the supplied
  `sig`, and the visit is treated as having no active promo at all (see the
  `same-day-rebooking-promo-landing` capability for the verifying side)

### Requirement: The discount only applies once the order subtotal reaches $99
The system SHALL ensure the $10 discount — whether shown in display or applied automatically at
checkout — never takes effect below a $99 order subtotal, enforced by Square's own
`CatalogPricingRule.minimumOrderSubtotalMoney`, not only by client-side logic.

#### Scenario: One-time Catalog setup encodes the $99 floor
- **WHEN** the one-time Square Catalog objects for this automation (discount, customer group,
  product set, pricing rule) are provisioned
- **THEN** the pricing rule's `minimumOrderSubtotalMoney` is set to $99.00, so Square itself
  refuses to apply the discount to any qualifying order below that subtotal regardless of any
  client-side state

### Requirement: New automation ships disabled by default
The `same_day_rebooking_discount` automation's `sms_automation` row SHALL default to
`enabled = false` when seeded by its migration.

#### Scenario: Fresh deployment has the automation off
- **WHEN** the migration that seeds `same_day_rebooking_discount` runs
- **THEN** `SELECT enabled FROM sms_automation WHERE automation_key =
  'same_day_rebooking_discount'` returns `false` until an OWNER explicitly turns it on
