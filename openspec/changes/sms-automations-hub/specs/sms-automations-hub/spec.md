## ADDED Requirements

### Requirement: Owner can see every SMS automation, whether it's on, and who it targets
The system SHALL expose `GET /api/owner/automations`, OWNER-only, returning every registered
automation with its enabled state, a plain-English audience description, and a 30-day sent count.

#### Scenario: Both automations visible
- **WHEN** an OWNER loads `/owner/automations`
- **THEN** the page shows a card each for `four_hand_request` and `checkout_review_request`,
  each showing its own enabled/disabled state and audience description

### Requirement: Owner can toggle an automation on or off
The system SHALL expose `PUT /api/owner/automations/{key}`, OWNER-only, updating that
automation's enabled state. A disabled automation's template SHALL NOT send, regardless of any
other condition being met.

#### Scenario: Disabling an automation stops new sends
- **WHEN** an OWNER disables `checkout_review_request`
- **THEN** a subsequent qualifying in-salon checkout does not result in any new SMS being sent for
  that automation, and `sendTemplated` for its template returns `{"sent": false, "reason":
  "automation_disabled"}`

#### Scenario: Non-owner cannot toggle
- **WHEN** a PROVIDER or MANAGER calls `PUT /api/owner/automations/{key}`
- **THEN** the response is `403 Forbidden`

### Requirement: A newly-added automation is never enabled by default
The system SHALL default `sms_automation.enabled` to `false` at the schema level, and any
automation added after this change SHALL be seeded disabled unless a migration explicitly states
otherwise with owner sign-off. An automation's template SHALL NOT send until an OWNER has
explicitly enabled it at least once.

#### Scenario: A freshly-added automation cannot fire on deploy
- **WHEN** a new automation's migration runs without an explicit `enabled = true` override
- **THEN** that automation is disabled, and any attempt to send its template returns
  `{"sent": false, "reason": "automation_disabled"}` until an OWNER enables it

### Requirement: Every outbound and inbound SMS is logged, regardless of outcome
The system SHALL record one `sms_message` row for every outbound send attempt (sent or not, with
its `reason` if not) and every inbound message received, independent of whether it matched a
pending automation flow.

#### Scenario: A blocked send is still logged
- **WHEN** a send is blocked by the automation-disabled gate, the no-consent gate, or a Twilio
  failure
- **THEN** an `sms_message` row is written recording the attempt and its `reason`

#### Scenario: An inbound reply that matches nothing is still logged
- **WHEN** an inbound SMS arrives with no matching `AWAITING_REPLY` flow (unsolicited, expired, or
  unrelated)
- **THEN** an `sms_message` row (`direction = INBOUND`) is written, and no outbound reply is sent

### Requirement: Owner can search the full SMS activity log
The system SHALL expose `GET /api/owner/automations/activity`, OWNER-only, returning
`sms_message` rows filterable by phone number, direction, and automation key.

#### Scenario: Filter by phone number
- **WHEN** an OWNER filters the activity view by a specific phone number
- **THEN** only `sms_message` rows for that number are returned, in both directions

### Requirement: Inbound messages carry a genuine read/unread state, independent of automation matching
The system SHALL track whether each inbound `sms_message` has been read (`read_at`, nullable,
inbound-only), regardless of whether that message matched a pending automation flow. The system
SHALL expose the current unread count and a way to mark a message read, both OWNER-only.

#### Scenario: An unmatched inbound text is unread by default
- **WHEN** a customer sends an SMS to the number that doesn't match any pending automation flow
- **THEN** the resulting `sms_message` row has `read_at = null` and is included in the unread count

#### Scenario: Marking a message read removes it from the unread count
- **WHEN** an OWNER marks an inbound message as read
- **THEN** its `read_at` is set and the unread count decreases by one

#### Scenario: Marking an already-read message read again is a no-op
- **WHEN** an OWNER marks an already-read message as read a second time
- **THEN** the original `read_at` value is unchanged and no error is returned

### Requirement: MANAGER role has no access to the automations hub in this change
The system SHALL restrict every endpoint under `/api/owner/automations/**` to OWNER only. A
limited MANAGER view is explicitly out of scope for this change (see design.md D9/Open Questions).

#### Scenario: Manager is denied
- **WHEN** a MANAGER calls any `/api/owner/automations/**` endpoint, including the activity log or
  unread count
- **THEN** the response is `403 Forbidden`

## MODIFIED Requirements

*(none — this file only adds the hub's own requirements; changes to `sms-automation-platform`
itself are specified in `../sms-automation-platform/spec.md`)*
