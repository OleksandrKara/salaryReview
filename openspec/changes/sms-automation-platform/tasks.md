## 1. Backend (salaryReview) — schema and config

- [x] 1.1 Create `V46__twilio_sms_config.sql` — single-row `twilio_sms_config` table
      (`account_sid`, `api_key`, `api_secret`, `from_phone_number`, `updated_at`, `updated_by`),
      seeded with all four credential fields `NULL`
- [x] 1.2 Create `TwilioSmsConfig` JPA entity (direct structural copy of
      `TelegramNotificationConfig`)
- [x] 1.3 Create `TwilioSmsConfigRepository` (`getSingleton()` helper, same shape as
      `TelegramNotificationConfigRepository`)
- [x] 1.4 Create `TwilioSmsConfigService` — `get()`/`update(accountSid, apiKey, apiSecret,
      fromPhoneNumber, updatedBy)` with the same null-vs-empty-string contract as
      `TelegramConfigService`

## 2. Backend (salaryReview) — template registry and compliance gate

- [x] 2.1 Create `SmsMessageClass` enum: `TRANSACTIONAL`, `MARKETING`
- [x] 2.2 Create `SmsTemplate` record: `key`, `messageClass`, a render function/template string
      taking `Map<String,String> variables`
- [x] 2.3 Create `SmsTemplateRegistry` with one entry: `four_hand_request_received`
      (`TRANSACTIONAL`) — see design.md D2 for example copy
- [x] 2.4 Create `SmsConsentRepository` (plain `JdbcTemplate`, mirroring
      `MarketingContactsRepository`'s style) — `hasMarketingConsent(String phoneNumber): boolean`,
      reading `marketing.contacts.sms_marketing_consent`, treating `NULL` as `false`
- [x] 2.5 Create `TwilioSmsClient` — hand-rolled `java.net.http.HttpClient` POST to
      `https://api.twilio.com/2010-04-01/Accounts/{account_sid}/Messages.json`, HTTP Basic Auth
      (`api_key` as username, `api_secret` as password), form-encoded `To`/`From`/`Body`
- [x] 2.6 Create `TwilioSmsService.sendTemplated(templateKey, phoneNumber, variables) ->
      SmsSendResult(sent, reason)`: unknown template → `reason="unknown_template"`; `MARKETING`
      class + no consent → `reason="no_consent"`; credentials unset → `reason="not_configured"`;
      Twilio call fails → `reason="send_failed"`; never throws

## 3. Backend (salaryReview) — internal + owner endpoints

- [x] 3.1 Add `POST /api/internal/notifications/sms/send` to the existing
      `InternalNotificationController` (`SmsSendRequest(templateKey, phoneNumber,
      variables)` body, same `X-Internal-Api-Key` check already in place, response
      `{"sent": boolean, "reason": string|null}`)
- [x] 3.2 Create `TwilioSmsSettingsController` at `/api/owner/settings/sms` (GET masks
      `api_key`/`api_secret` to last-4, PUT never accepts a masked value back — mirrors
      `TelegramSettingsController` exactly)

## 4. Backend (salaryReview) — tests

- [x] 4.1 `SmsTemplateRegistryTest`: `four_hand_request_received` is registered as
      `TRANSACTIONAL`; unknown key lookup fails predictably (not an exception the caller has to
      catch)
- [x] 4.2 `TwilioSmsServiceTest`: TRANSACTIONAL template sends regardless of consent;
      hypothetical MARKETING template (test-only registry entry) is blocked when consent is
      false/null and allowed when true; unset credentials → `not_configured`, no HTTP attempt
- [x] 4.3 `InternalNotificationControllerTest`: new `/sms/send` method — 401 on bad/missing key,
      200 with `sent`/`reason` on both allowed and blocked outcomes
- [x] 4.4 `TwilioSmsSettingsControllerTest`: masking + null-vs-empty-string PUT semantics (mirrors
      `TelegramSettingsControllerTest`)

## 5. Frontend (salaryReview) — owner settings page

- [x] 5.1 Add `TwilioSmsSettingsDto`/`TwilioSmsSettingsUpdateRequest` types
- [x] 5.2 Add `getTwilioSmsSettings`/`updateTwilioSmsSettings` to `serverApi.ts`/`api.ts`
- [x] 5.3 Create `app/api/owner/settings/sms/route.ts` proxy (mirrors the Telegram one)
- [x] 5.4 Create `app/owner/settings/sms/page.tsx` + `TwilioSmsSettingsForm.tsx` (mirrors
      `TelegramSettingsForm.tsx`'s masked-field/leave-blank-to-keep pattern)
- [x] 5.5 Add a nav entry + i18n key next to the existing Telegram settings link

## 6. mani + akluxnails-home — trigger the first automation

- [x] 6.1 mani: create `app/integrations/sms/notifier.py` (`notify_four_hand_request_sms`),
      mirroring `app/integrations/telegram/notifier.py`'s fail-open shape, calling
      `POST {internal_api_base_url}/api/internal/notifications/sms/send`
- [x] 6.2 mani: call it from `submit_four_hand_request()` right alongside the existing
      `notify_four_hand_request` (Telegram) call, passing customer name/phone + preferred time as
      `variables`
- [x] 6.3 akluxnails-home: create `lib/sms.ts` (`notifyFourHandRequestSms`), mirroring
      `lib/telegram.ts`
- [x] 6.4 akluxnails-home: call it from the four-hand branch of `app/api/booking/create/route.ts`
      right alongside the existing `notifyFourHandRequest` (Telegram) call

## 7. Verification

- [x] 7.1 `mvn test` — all new + existing tests pass (346 tests, 0 failures, 0 errors, incl. all 4
      SMS test classes)
- [x] 7.2 `tsc`/`eslint` clean on both booking apps
- [ ] 7.3 Once the owner provides real Twilio credentials: set them via `/owner/settings/sms`,
      submit one real 4-hand request against Square Sandbox on each booking app, confirm a real
      SMS arrives, confirm no SMS attempt is made if credentials are cleared. **Blocked**:
      credentials are set (real Account SID + API Key/Secret) and the account is type "Full" (not
      trial, so the earlier magic-keyword trial workaround no longer applies) — but a real send
      via `POST /api/internal/notifications/sms/send` failed with Twilio error 21660
      ("Mismatch between the 'From' number +17372324091 and the account ...") because this
      Twilio account currently owns **zero phone numbers**
      (`GET /IncomingPhoneNumbers.json` returns an empty list). The configured `from_phone_number`
      doesn't actually belong to this account. Needs the owner to buy/assign a real number on this
      Twilio account (Console → Phone Numbers → Buy a number) and update
      `/owner/settings/sms`'s From field to match before this can be re-tested.
