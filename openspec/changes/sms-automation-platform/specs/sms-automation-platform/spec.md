## ADDED Requirements

### Requirement: Internal SMS relay endpoint sends only registered templates
The system SHALL expose `POST /api/internal/notifications/sms/send`, gated by the same shared
`X-Internal-Api-Key` header used by the existing Telegram relay endpoint. The request body SHALL
be `{templateKey, phoneNumber, variables}` — it SHALL NOT accept a caller-supplied compliance
class; the message's `TRANSACTIONAL`/`MARKETING` classification SHALL be resolved solely from the
server-side `SmsTemplateRegistry` entry for `templateKey`.

#### Scenario: Unknown template key
- **WHEN** a caller requests a `templateKey` not present in `SmsTemplateRegistry`
- **THEN** the response is `200 {"sent": false, "reason": "unknown_template"}` — no Twilio call is
  attempted, no exception is thrown

#### Scenario: Missing or wrong internal API key
- **WHEN** the request omits `X-Internal-Api-Key` or sends the wrong value
- **THEN** the response is `401`, matching the existing Telegram endpoint's behavior

### Requirement: MARKETING-class templates require real consent; TRANSACTIONAL-class do not
The system SHALL check `marketing.contacts.sms_marketing_consent` (by normalized phone number)
before sending any template registered as `MARKETING`, and SHALL NOT send if that value is not
`true` (including when it is `NULL`). The system SHALL send `TRANSACTIONAL`-class templates
regardless of that value.

#### Scenario: Marketing template blocked by missing consent
- **WHEN** a `MARKETING`-class template is requested for a phone number whose
  `sms_marketing_consent` is `NULL` or `false`
- **THEN** the response is `{"sent": false, "reason": "no_consent"}` and no Twilio API call is made

#### Scenario: Marketing template sent with real consent
- **WHEN** a `MARKETING`-class template is requested for a phone number whose
  `sms_marketing_consent` is `true`, and Twilio credentials are configured
- **THEN** the system calls Twilio and returns `{"sent": true, "reason": null}` on success

#### Scenario: Transactional template sent without any consent check
- **WHEN** the `four_hand_request_received` (`TRANSACTIONAL`) template is requested for any valid
  phone number, and Twilio credentials are configured
- **THEN** the system sends the message without checking `sms_marketing_consent` at all

### Requirement: Sending never throws when Twilio credentials are absent or invalid
The system SHALL return a `sent: false` response with a populated `reason` — never a 500 or an
uncaught exception — whenever Twilio credentials are unset, incomplete, or Twilio's API call
fails for any reason.

#### Scenario: Credentials not yet configured
- **WHEN** `twilio_sms_config` has null `account_sid`/`api_key`/`api_secret`
- **THEN** any send attempt (of any template) returns `{"sent": false, "reason":
  "not_configured"}` without attempting an HTTP call to Twilio

#### Scenario: Twilio API call fails
- **WHEN** Twilio's API returns a non-2xx response or the request errors (timeout, network
  failure)
- **THEN** the response is `{"sent": false, "reason": "send_failed"}`, logged at `warn`, and the
  caller (mani/akluxnails-home's booking flow) is unaffected

### Requirement: Owner can view and update Twilio credentials without ever seeing them re-exposed
The system SHALL expose `GET/PUT /api/owner/settings/sms`, gated by the existing OWNER-only
`/api/owner/**` security rule. `GET` SHALL return the API key and API secret masked to their last
4 characters (or omitted if unset) and the sending phone number unmasked. `PUT` SHALL treat a
`null` field as "leave unchanged" and an empty string as "clear this field," and SHALL NOT accept
a previously-returned masked value as a way to set the real credential.

#### Scenario: Owner sets credentials for the first time
- **WHEN** an OWNER submits `PUT /api/owner/settings/sms` with real values for all four fields
- **THEN** subsequent `GET` calls show the masked key/secret and the real phone number, and SMS
  sending becomes possible

#### Scenario: Non-owner access blocked
- **WHEN** a PROVIDER or MANAGER calls `GET /api/owner/settings/sms`
- **THEN** the response is `403 Forbidden`
