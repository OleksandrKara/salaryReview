## MODIFIED Requirements

### Requirement: Internal SMS relay endpoint sends only registered, enabled templates
The system SHALL expose `POST /api/internal/notifications/sms/send`, gated by the same shared
`X-Internal-Api-Key` header used by the existing Telegram relay endpoint. The request body SHALL
be `{templateKey, phoneNumber, variables}` — it SHALL NOT accept a caller-supplied compliance
class; the message's `TRANSACTIONAL`/`MARKETING` classification SHALL be resolved solely from the
server-side `SmsTemplateRegistry` entry for `templateKey`. If that template's `automationKey` is
registered as disabled in `sms_automation`, the system SHALL NOT send.

#### Scenario: Unknown template key
- **WHEN** a caller requests a `templateKey` not present in `SmsTemplateRegistry`
- **THEN** the response is `200 {"sent": false, "reason": "unknown_template"}` — no Twilio call is
  attempted, no exception is thrown

#### Scenario: Missing or wrong internal API key
- **WHEN** the request omits `X-Internal-Api-Key` or sends the wrong value
- **THEN** the response is `401`, matching the existing Telegram endpoint's behavior

#### Scenario: Template's automation is disabled
- **WHEN** a `templateKey` whose `automationKey` is disabled in `sms_automation` is requested
- **THEN** the response is `{"sent": false, "reason": "automation_disabled"}` and no Twilio call is
  attempted

### Requirement: Every send attempt is recorded to the SMS activity log
The system SHALL insert one `sms_message` row (`direction = OUTBOUND`) for every call to
`sendTemplated`, regardless of outcome, recording the phone number, template key, rendered body,
resulting status, and reason (if any).

#### Scenario: A successful send is logged
- **WHEN** `sendTemplated` completes with `sent: true`
- **THEN** an `sms_message` row is written with `status` reflecting success and `reason` null

#### Scenario: A blocked or failed send is still logged
- **WHEN** `sendTemplated` returns `sent: false` for any reason (`no_consent`,
  `automation_disabled`, `not_configured`, `send_failed`, `unknown_template`)
- **THEN** an `sms_message` row is written recording that outcome and its reason
