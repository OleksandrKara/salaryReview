## ADDED Requirements

### Requirement: An in-salon completed payment enqueues a delayed rating request
The system SHALL receive Square's `payment.updated` webhook, verify its signature, and — for a
`COMPLETED` payment whose Order has no linked online booking and whose customer has a phone number
on file — create an `sms_reply_flow` row due to send 2 minutes later.

#### Scenario: Walk-in card payment enqueues a flow
- **WHEN** a `payment.updated` webhook reports a `COMPLETED` payment for an Order with no booking
  linkage, and the Order's customer has a phone number on file
- **THEN** one `sms_reply_flow` row is created with `state = AWAITING_SEND` and
  `send_due_at` two minutes in the future

#### Scenario: Online-booking payment does not enqueue a flow
- **WHEN** a `payment.updated` webhook reports a `COMPLETED` payment for an Order that is linked to
  an online booking
- **THEN** no `sms_reply_flow` row is created

#### Scenario: No phone on file
- **WHEN** a qualifying walk-in payment's customer has no phone number on file in Square
- **THEN** no `sms_reply_flow` row is created and no exception is raised

#### Scenario: Invalid webhook signature
- **WHEN** a request to the Square webhook endpoint has a missing or incorrect signature
- **THEN** the response is `401` and no flow row is created

#### Scenario: A salaryReview Manual Adjustment never triggers this automation
- **WHEN** an OWNER or MANAGER records a Manual Adjustment (a cash-note settlement correction) in
  salaryReview
- **THEN** no Square `payment.updated` event is produced (Manual Adjustments never write to
  Square), so no `sms_reply_flow` row is ever created for it

### Requirement: The rating request sends once, 2 minutes after checkout
The system SHALL send the `checkout_rating_request` (`TRANSACTIONAL`) template to an
`AWAITING_SEND` flow row once its `send_due_at` has passed, then transition it to
`AWAITING_REPLY` with a 24-hour reply window.

#### Scenario: Due flow sends
- **WHEN** an `AWAITING_SEND` row's `send_due_at` is in the past
- **THEN** the rating SMS is sent exactly once and the row transitions to `AWAITING_REPLY`

#### Scenario: Not-yet-due flow does not send early
- **WHEN** an `AWAITING_SEND` row's `send_due_at` is still in the future
- **THEN** no SMS is sent for that row yet

#### Scenario: Reply window expires
- **WHEN** an `AWAITING_REPLY` row's `reply_expires_at` has passed with no reply received
- **THEN** the row transitions to `EXPIRED` and a subsequent inbound reply from that phone number no
  longer matches it

### Requirement: A reply containing "5" gets the Google review link; anything else gets the feedback form
The system SHALL receive Twilio's inbound-SMS webhook, verify its signature, log the message, and
— if it matches an `AWAITING_REPLY` flow for the sender's phone number — reply with one of two
click-tracked short links based on whether the message body contains the digit "5".

#### Scenario: Rating of 5 gets the review link
- **WHEN** an inbound reply matching an `AWAITING_REPLY` flow contains the digit "5"
- **THEN** a `checkout_review_positive` (`TRANSACTIONAL`) message containing a short link to the
  Google review page is sent, and the flow transitions to `COMPLETED`

#### Scenario: Any other rating gets the feedback form
- **WHEN** an inbound reply matching an `AWAITING_REPLY` flow does not contain the digit "5"
- **THEN** a `checkout_review_negative` (`TRANSACTIONAL`) message containing a short link to the
  feedback form is sent, and the flow transitions to `COMPLETED`

#### Scenario: Reply with no matching flow
- **WHEN** an inbound SMS arrives with no `AWAITING_REPLY` flow for that phone number
- **THEN** the message is logged and no automated reply is sent

#### Scenario: Invalid webhook signature
- **WHEN** a request to the Twilio inbound-SMS endpoint has a missing or incorrect signature
- **THEN** the response is `401` and no message is logged or processed

### Requirement: Review-link clicks are tracked
The system SHALL expose `GET /r/{id}`, publicly accessible, which records the first click on a
review/feedback link and redirects to its real destination.

#### Scenario: First click is recorded
- **WHEN** a short link is followed for the first time
- **THEN** the corresponding `sms_message` row's `clicked_at` is set and the browser is redirected
  to the real destination URL (Google review page or feedback form, per that message's
  `link_target`)

#### Scenario: Repeat clicks don't overwrite the first timestamp
- **WHEN** the same short link is followed again after already being clicked once
- **THEN** the redirect still happens, but `clicked_at` keeps its original value
