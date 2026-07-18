## 1. Backend (salaryReview) — schema and config

- [ ] 1.1 Create `V46__twilio_sms_config.sql` — single-row `twilio_sms_config` table
      (`account_sid`, `api_key`, `api_secret`, `from_phone_number`, `updated_at`, `updated_by`),
      seeded with all four credential fields `NULL`
- [ ] 1.2 Create `TwilioSmsConfig` JPA entity (direct structural copy of
      `TelegramNotificationConfig`)
- [ ] 1.3 Create `TwilioSmsConfigRepository` (`getSingleton()` helper, same shape as
      `TelegramNotificationConfigRepository`)
- [ ] 1.4 Create `TwilioSmsConfigService` — `get()`/`update(accountSid, apiKey, apiSecret,
      fromPhoneNumber, updatedBy)` with the same null-vs-empty-string contract as
      `TelegramConfigService`

## 2. Backend (salaryReview) — template registry and compliance gate

- [ ] 2.1 Create `SmsMessageClass` enum: `TRANSACTIONAL`, `MARKETING`
- [ ] 2.2 Create `SmsTemplate` record: `key`, `messageClass`, a render function/template string
      taking `Map<String,String> variables`
- [ ] 2.3 Create `SmsTemplateRegistry` with one entry: `four_hand_request_received`
      (`TRANSACTIONAL`) — see design.md D2 for example copy
- [ ] 2.4 Create `SmsConsentRepository` (plain `JdbcTemplate`, mirroring
      `MarketingContactsRepository`'s style) — `hasMarketingConsent(String phoneNumber): boolean`,
      reading `marketing.contacts.sms_marketing_consent`, treating `NULL` as `false`
- [ ] 2.5 Create `TwilioSmsClient` — hand-rolled `java.net.http.HttpClient` POST to
      `https://api.twilio.com/2010-04-01/Accounts/{account_sid}/Messages.json`, HTTP Basic Auth
      (`api_key` as username, `api_secret` as password), form-encoded `To`/`From`/`Body`
- [ ] 2.6 Create `TwilioSmsService.sendTemplated(templateKey, phoneNumber, variables) ->
      SmsSendResult(sent, reason)`: unknown template → `reason="unknown_template"`; `MARKETING`
      class + no consent → `reason="no_consent"`; credentials unset → `reason="not_configured"`;
      Twilio call fails → `reason="send_failed"`; never throws

## 3. Backend (salaryReview) — internal + owner endpoints

- [ ] 3.1 Add `POST /api/internal/notifications/sms/send` to the existing
      `InternalNotificationController` (`SmsSendRequest(templateKey, phoneNumber,
      variables)` body, same `X-Internal-Api-Key` check already in place, response
      `{"sent": boolean, "reason": string|null}`)
- [ ] 3.2 Create `TwilioSmsSettingsController` at `/api/owner/settings/sms` (GET masks
      `api_key`/`api_secret` to last-4, PUT never accepts a masked value back — mirrors
      `TelegramSettingsController` exactly)

## 4. Backend (salaryReview) — tests

- [ ] 4.1 `SmsTemplateRegistryTest`: `four_hand_request_received` is registered as
      `TRANSACTIONAL`; unknown key lookup fails predictably (not an exception the caller has to
      catch)
- [ ] 4.2 `TwilioSmsServiceTest`: TRANSACTIONAL template sends regardless of consent;
      hypothetical MARKETING template (test-only registry entry) is blocked when consent is
      false/null and allowed when true; unset credentials → `not_configured`, no HTTP attempt
- [ ] 4.3 `InternalNotificationControllerTest`: new `/sms/send` method — 401 on bad/missing key,
      200 with `sent`/`reason` on both allowed and blocked outcomes
- [ ] 4.4 `TwilioSmsSettingsControllerTest`: masking + null-vs-empty-string PUT semantics (mirrors
      `TelegramSettingsControllerTest`)

## 5. Frontend (salaryReview) — owner settings page

- [ ] 5.1 Add `TwilioSmsSettingsDto`/`TwilioSmsSettingsUpdateRequest` types
- [ ] 5.2 Add `getTwilioSmsSettings`/`updateTwilioSmsSettings` to `serverApi.ts`/`api.ts`
- [ ] 5.3 Create `app/api/owner/settings/sms/route.ts` proxy (mirrors the Telegram one)
- [ ] 5.4 Create `app/owner/settings/sms/page.tsx` + `TwilioSmsSettingsForm.tsx` (mirrors
      `TelegramSettingsForm.tsx`'s masked-field/leave-blank-to-keep pattern)
- [ ] 5.5 Add a nav entry + i18n key next to the existing Telegram settings link

## 6. mani + akluxnails-home — trigger the first automation

- [ ] 6.1 mani: create `app/integrations/sms/notifier.py` (`notify_four_hand_request_sms`),
      mirroring `app/integrations/telegram/notifier.py`'s fail-open shape, calling
      `POST {internal_api_base_url}/api/internal/notifications/sms/send`
- [ ] 6.2 mani: call it from `submit_four_hand_request()` right alongside the existing
      `notify_four_hand_request` (Telegram) call, passing customer name/phone + preferred time as
      `variables`
- [ ] 6.3 akluxnails-home: create `lib/sms.ts` (`notifyFourHandRequestSms`), mirroring
      `lib/telegram.ts`
- [ ] 6.4 akluxnails-home: call it from the four-hand branch of `app/api/booking/create/route.ts`
      right alongside the existing `notifyFourHandRequest` (Telegram) call

## 7. Verification

- [ ] 7.1 `mvn test` — all new + existing tests pass
- [ ] 7.2 `tsc`/`eslint` clean on both booking apps
- [ ] 7.3 Once the owner provides real Twilio credentials: set them via `/owner/settings/sms`,
      submit one real 4-hand request against Square Sandbox on each booking app, confirm a real
      SMS arrives, confirm no SMS attempt is made if credentials are cleared
