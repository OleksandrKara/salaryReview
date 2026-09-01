## 1. Backend — Migrations and domain

- [x] 1.1 `V139__seo_connection.sql` — `seo_connection(id, business_id UNIQUE FK, gsc_service_account_json_encrypted, ga4_property_id, ga4_measurement_id, pagespeed_api_key_encrypted, connected_by_user_id, connected_at, last_sync_at, last_sync_error TEXT)`
- [x] 1.2 `V140__seo_metrics_tables.sql` — `seo_search_metrics_snapshot(id, business_id, date, query, page, clicks, impressions, ctr, position, UNIQUE(business_id, date, query, page))`, `seo_page_snapshot(id, business_id, date, url, strategy, performance_score, lcp_ms, cls, fcp_ms, tbt_ms, UNIQUE(business_id, date, url, strategy))` — `strategy` stored as `MOBILE`/`DESKTOP` (uppercase, matching `SquareProperties.Environment`'s existing enum-naming convention)
- [x] 1.3 `V141__seo_technical_issue.sql` — `seo_technical_issue(id, business_id, issue_type, detail, severity, metric_value, first_seen_at, resolved_at)` — `severity` stored as `NEEDS_IMPROVEMENT`/`POOR`/`ADVISORY` (same uppercase convention)
- [x] 1.4 `V142__business_feature_seo_monitoring.sql` — insert `seo-monitoring.enabled = false` for every existing business row via `INSERT ... SELECT id FROM business` (not hardcoded business_id=1 like V108 — explicit for every business, not absent — design.md D5)
- [x] 1.5 JPA entities + repositories for all 4 new tables, following the existing `SquareConnection`/`SquareConnectionRepository` naming/annotation style exactly. `./mvnw -o compile` clean. **Not yet applied to any database** (migrations only run via the app's own Flyway-on-startup — not run manually against the live DB this session, deliberately, since this is new schema in a different, larger production app than akluxnails-home).

## 2. Backend — Credential handling

- [x] 2.1 `SeoCredentialCipher` (mirrors `SquareCredentialCipher` exactly — design.md D1), reads `seo.credentials.master-key` / `SEO_CREDENTIALS_MASTER_KEY`. Wired into `application.yml` and `docker-compose.yml` explicitly (not just one of the two — see the docker-compose.yml comment citing the real prior incident where `SQUARE_CREDENTIALS_MASTER_KEY` was referenced in `application.yml` but never passed through, causing every migrated endpoint to 500)
- [x] 2.2 `SeoConnectionService` (package `com.salonreview.seo`) — get/connect, encrypts on write, decrypts only in-memory (`decryptedServiceAccountJson`/`decryptedPagespeedApiKey`, for Phase 3's API clients only), `serviceAccountEmail()` extracts `client_email` from the parsed JSON for a meaningful settings-UI display (a masked substring of a JSON blob would show nothing useful, unlike a token)
- [x] 2.3 `SeoConnectionController` — `GET`/`PUT /api/owner/settings/seo` (owner-only, falls under the existing `/api/owner/**` matcher, confirmed — no new security config needed), validates the service-account JSON parses and has `client_email`/`private_key` before saving. Path is `/api/owner/settings/seo` (not `/seo-connection` as originally sketched) to match the exact `/owner/settings/{square,telegram,sms}` sibling pattern.
- [x] 2.4 Unit tests for `SeoConnectionService` (8 tests: valid/invalid/incomplete JSON, null/blank keeps existing, first-connect requires both credentials, email extraction, key masking) — all passing locally (`./mvnw test -Dtest=SeoConnectionServiceTest`)

## 3. Backend — Google API clients

- [x] 3.1 **Revised from the original plan**: no Google client library added to `pom.xml` — hand-rolled instead, matching `SquareClient`'s own "thin custom `RestClient`, not a heavyweight SDK" convention. `GoogleServiceAccountAuth` (new, package-private) hand-signs the RS256 JWT-bearer assertion via `java.security` (no JWT library either — same "hand-roll crypto with java.security, no new dependency" convention `SquareCredentialCipher`/`SeoCredentialCipher` already established) and exchanges it for an access token, cached until ~60s before expiry. `GoogleRestClients` (new, package-private factory) wires every client's `RestClient` with an explicit `MappingJackson2HttpMessageConverter` bound to a `com.fasterxml.jackson.databind.ObjectMapper` — **required**, not optional: this app's default Spring message converters are wired for a newer Jackson major version (`tools.jackson`), and a real runtime `InvalidDefinitionException` was hit wiring this up before adding this factory (both Jackson generations' classes are present on the classpath, so it's a runtime failure, not a compile error) — `SquareClient` already avoids this the same way, just re-derived here.
- [x] 3.2 `SearchConsoleClient` — `sites()` (discovers the actual registered site URL/property type — confirmed via real API call it's `sc-domain:akluxnails.com`, a Domain property, not assumable) and `queryPerformance(siteUrl, date, rowLimit)`. One instance per business (constructed fresh from a decrypted service-account JSON), not a shared singleton — design.md D4.
- [x] 3.3 `GoogleAnalyticsClient` — `pageViewsByPath(propertyId, date, limit)`.
- [x] 3.4 `PageSpeedInsightsClient` — `check(url, strategy)`, plain API-key auth (no `GoogleServiceAccountAuth` involved, unlike the two above).
- [x] 3.5 **Verified against AK.LUX.NAILS' real, already-connected credentials** (not just mocks) before considering this phase done — caught and fixed 2 real bugs a mock alone would have missed: (a) the Jackson-version mismatch above, only surfaced by an actual Spring context/HTTP round-trip; (b) `queryPerformance`/`pageViewsByPath` originally built their request bodies as hand-formatted JSON strings, which the real Jackson converter re-serializes as an escaped JSON *string* rather than writing through as an object — fixed by building real `Map`/`List` request bodies instead. All three clients now return real data end-to-end (real site, real GA4 page views, real PageSpeed score) confirmed manually, then the verification code was deleted (never committed) before writing the permanent test suite below.
- [x] 3.6 Permanent test suite: `GoogleServiceAccountAuthTest` (JWT structurally valid + independently signature-verifiable against the matching public key using a real generated RSA keypair — not just "some string came back"; token caching behavior), `SearchConsoleClientTest`/`GoogleAnalyticsClientTest`/`PageSpeedInsightsClientTest` (via `MockRestServiceServer.bindTo(RestClient.Builder)` — a real request-body assertion catches the exact double-JSON-encoding bug found in 3.5 by construction, not by luck). 17 tests, all passing (`./mvnw test -Dtest=GoogleServiceAccountAuthTest,SearchConsoleClientTest,GoogleAnalyticsClientTest,PageSpeedInsightsClientTest`).

## 4. Backend — Threshold/flagging engine

- [ ] 4.1 `CoreWebVitalsThresholds` — named constants per design.md D3, each with a comment citing Google's published source and the date last verified
- [ ] 4.2 `SeoIssueFlaggingService` — given a new `seo_page_snapshot`/`seo_search_metrics_snapshot` row, creates/resolves `seo_technical_issue` rows per D3's rules
- [ ] 4.3 Unit tests: exact boundary values for every threshold (2500ms passes, 2501ms flags; etc.), auto-resolve transition, CTR heuristic with the default 50-impression floor

## 5. Backend — Scheduled jobs

- [ ] 5.1 `SeoSearchConsoleSyncScheduler` — daily, iterates `business JOIN seo_connection JOIN business_feature(seo-monitoring.enabled=true)`, per-business ShedLock key (design.md D4)
- [ ] 5.2 `SeoPageSpeedSyncScheduler` — weekly, same iteration pattern, mobile + desktop per business's homepage URL
- [ ] 5.3 Both jobs write `last_sync_at`/`last_sync_error` to `seo_connection` on success/failure — never silent-fail (design.md Risks)
- [ ] 5.4 Integration test: two businesses' `seo_connection` rows, assert jobs never cross-contaminate data or share a lock

## 6. Backend — Dashboard API

- [ ] 6.1 `SeoDashboardController` — `GET /api/owner/marketing/seo/overview` (trend series + latest CWV + active issues), owner+manager gated matching the existing `/api/owner/marketing/**` pattern
- [ ] 6.2 `POST /api/owner/marketing/seo/sync` — manual on-demand refresh (owner-only), same shape as the existing Square `/api/sync` button

## 7. Frontend — Settings page

- [ ] 7.1 `app/owner/settings/seo/page.tsx` — form for the 3 credentials, mirroring `app/owner/settings/telegram`'s layout/pattern
- [ ] 7.2 `app/api/owner/settings/seo-connection/route.ts` proxy route

## 8. Frontend — Dashboard page

- [ ] 8.1 `app/owner/marketing/seo/page.tsx` — trend chart, keyword table, CWV cards (color-coded: green/amber/red against D3's thresholds), active-issues list with recommendation text
- [ ] 8.2 Add the tab to `MarketingTabs.tsx`, gated on `seo-monitoring.enabled` (hidden entirely, not just disabled, when off — design.md D6)
- [ ] 8.3 `app/api/owner/marketing/seo/route.ts` + `.../sync/route.ts` proxy routes

## 9. Migration of AK.LUX.NAILS onto this (retiring the manual scripts)

- [ ] 9.1 Enable `seo-monitoring.enabled` for Business A (AK.LUX.NAILS)
- [ ] 9.2 Connect its real credentials (already exist at `~/seo-monitoring/credentials/` on the
      akluxnails-home VPS — see [[seo_monitoring_runbook]]) through the new settings page
- [ ] 9.3 Confirm the dashboard's numbers match what `check.mjs`/`psi.mjs` already showed
- [ ] 9.4 Once confirmed, retire the manual scripts/runbook (or keep the runbook only as the
      onboarding-instructions doc for *creating* the Google-side credentials, since that part isn't
      replaced by this change — only the query/storage/display side is)

## 10. Tests and verification

- [ ] 10.1 Full cross-tenant isolation suite (per proposal.md verification)
- [ ] 10.2 `./mvnw test` green throughout
- [ ] 10.3 Manual localhost check per repo convention before merge
