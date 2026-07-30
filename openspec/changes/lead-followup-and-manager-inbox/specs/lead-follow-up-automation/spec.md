## ADDED Requirements

### Requirement: A contact with no upcoming appointment within 2 minutes gets a helpful follow-up text
The system SHALL poll `marketing.contacts` for rows created at least 2 minutes ago that have not
yet been processed, and — for each one whose phone number resolves to no upcoming Square
appointment (checked live at send-time, not limited to bookings made through this specific contact
capture) — send one transactional, no-incentive follow-up text offering help finding a time,
provided the `lead_follow_up` automation is enabled.

#### Scenario: Unbooked contact gets the follow-up text
- **WHEN** a `marketing.contacts` row is 2 or more minutes old, has never been processed by this
  automation, its phone number resolves to no Square customer with a not-cancelled booking whose
  `startAt` is still in the future, and `lead_follow_up` is enabled
- **THEN** the follow-up SMS is sent to that contact's phone number and a `lead_followup_send` row
  is written with `state = SENT`

#### Scenario: Contact with any upcoming appointment is skipped
- **WHEN** a `marketing.contacts` row reaches the 2-minute mark and its phone number resolves
  (either via `square_customer_id` already recorded on the contact, or via a live phone-number
  lookup) to a Square customer with at least one booking that hasn't happened yet and hasn't been
  cancelled, declined, or marked no-show — regardless of which channel that booking was made
  through
- **THEN** no SMS is sent, and a `lead_followup_send` row is written with `state = SKIPPED_BOOKED`

#### Scenario: Automation disabled
- **WHEN** a `marketing.contacts` row reaches the 2-minute mark, has no linked booking, but
  `lead_follow_up` is disabled
- **THEN** no SMS is sent, and a `lead_followup_send` row is written with `state = SKIPPED_DISABLED`

#### Scenario: A contact is never processed twice
- **WHEN** a `marketing.contacts` row already has a `lead_followup_send` row (in any state)
- **THEN** a later poll cycle does not re-evaluate or re-send for that contact

### Requirement: The follow-up message contains no discount, coupon, or promotional incentive
The system SHALL send only transactional copy for this automation — no discount, coupon, expiring
offer, or "book now and save" language — so it can be sent regardless of `sms_marketing_consent`.

#### Scenario: Message is sendable without marketing consent
- **WHEN** the `lead_follow_up` automation sends its follow-up text to a contact who has not opted
  into marketing SMS
- **THEN** the send is not blocked on consent grounds, since the template is classified
  transactional

### Requirement: New automation ships disabled by default
The `lead_follow_up` automation's `sms_automation` row SHALL default to `enabled = false` when
seeded by its migration.

#### Scenario: Fresh deployment has the automation off
- **WHEN** the migration that seeds `lead_follow_up` runs
- **THEN** `SELECT enabled FROM sms_automation WHERE automation_key = 'lead_follow_up'` returns
  `false` until an OWNER explicitly turns it on
