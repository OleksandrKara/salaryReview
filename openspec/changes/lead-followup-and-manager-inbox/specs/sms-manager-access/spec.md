## ADDED Requirements

### Requirement: MANAGER can read every customer conversation, grouped per phone number
The system SHALL let a MANAGER-role session list distinct customer conversations (one per phone
number, most-recent-first, with an unread count) and open the full chronological thread for any one
of them.

#### Scenario: Manager lists conversations
- **WHEN** a MANAGER-role session requests the conversation list
- **THEN** the response includes one entry per distinct phone number with a message in
  `sms_message`, ordered by most recent activity first

#### Scenario: Manager opens a thread
- **WHEN** a MANAGER-role session requests the thread for a specific phone number
- **THEN** every `sms_message` row for that phone number is returned in chronological order,
  regardless of which automation (if any) sent or matched each message

### Requirement: MANAGER can send a manual reply to a customer
The system SHALL let a MANAGER-role session send a freeform SMS to a specific phone number,
bypassing template selection and automation-enabled gating entirely, and log it into the same
activity log as every other message.

#### Scenario: Manual reply is sent and logged
- **WHEN** a MANAGER-role session submits a phone number and message body to the manual-reply
  endpoint
- **THEN** the message is sent via Twilio and a new `sms_message` row is written with
  `direction = OUTBOUND`, `automation_key = NULL`, `template_key = NULL`

#### Scenario: Manual reply is never blocked by marketing-consent status
- **WHEN** a MANAGER sends a manual reply to a phone number with no `sms_marketing_consent`
- **THEN** the send is not blocked, since a direct conversational reply is transactional under the
  standing SMS compliance rule

### Requirement: MANAGER cannot toggle automations on or off
The system SHALL continue to restrict `PUT /api/owner/automations/{key}` to the OWNER role only.

#### Scenario: Manager attempts to toggle an automation
- **WHEN** a MANAGER-role session calls the automation-toggle endpoint
- **THEN** the request is rejected (403) and the automation's enabled state is unchanged
