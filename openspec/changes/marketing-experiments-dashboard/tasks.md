## 1. Backend — DTOs and repository

- [x] 1.1 Create `MarketingDashboardDto` record: `available`, `landingPageSlug`,
      `experimentStatus`, `variants: List<VariantStat>`; static `unavailable(slug)` factory
      returning `available: false`, `experimentStatus: "none"`, empty `variants`
- [x] 1.2 Create `VariantStat` record: `variantId`, `name`, `weight`, `active`, `pageViews`,
      `bookingsCompleted`, `conversionRate`
- [x] 1.3 Create `MarketingDashboardRepository` (constructor-injected `JdbcTemplate`, no new
      `DataSource` bean — reuses the existing one): `findLandingPageId(slug)`,
      `findExperimentStatus(landingPageId)`, `findVariantStats(landingPageId)` (query joining
      `marketing.landing_variants` to `marketing.events` filtered `event_type = 'page_view'` and
      to `marketing.attribution`, grouped by variant)
- [x] 1.4 Every repository method: queries only, no exception handling here — let
      `DataAccessException` propagate to the service layer

## 2. Backend — Service and Controller

- [x] 2.1 `MarketingDashboardService.dashboard(String slug)`: look up the landing page id; return
      `MarketingDashboardDto.unavailable(slug)` when not found; otherwise fetch experiment status
      and variant stats and assemble the DTO
- [x] 2.2 Wrap the repository calls in a try/catch for `org.springframework.dao.DataAccessException`
      (covers `BadSqlGrammarException` when the `marketing` schema/tables don't exist yet);
      return `MarketingDashboardDto.unavailable(slug)` on catch, log at `warn`, never throw
- [x] 2.3 Compute `conversionRate = pageViews == 0 ? 0 : bookingsCompleted / (double) pageViews`
      per variant
- [x] 2.4 Create `MarketingDashboardController` — `GET /api/owner/marketing?slug=` (default
      `"mani"` when absent), delegating to `MarketingDashboardService`
- [x] 2.5 In `SecurityConfig.java`, add a one-line comment next to the existing `/api/owner/**` →
      `hasRole('OWNER')` matcher noting the new controller is covered by it (no functional change)

## 3. Backend — Tests

- [x] 3.1 `MarketingDashboardServiceTest` (Mockito, matching `OwnerOverviewServiceTest` style):
      conversion-rate math (including the `pageViews == 0` → `0`, not divide-by-zero, case) from
      a mocked repository
- [x] 3.2 Same test class: `dashboard()` returns `unavailable(slug)` (not a thrown exception) when
      the mocked repository raises `DataAccessException`
- [x] 3.3 Same test class: `dashboard()` returns `unavailable(slug)` when the landing page slug is
      not found

## 4. Frontend — API proxy and types

- [x] 4.1 Add `MarketingDashboardData` and `VariantStat` TypeScript types to
      `frontend/app/lib/types.ts`
- [x] 4.2 Create `frontend/app/api/owner/marketing/route.ts` using `forwardToBackend`, forwarding
      `?slug=` (mirrors `frontend/app/api/owner/overview/route.ts`)
- [x] 4.3 Add `getMarketingDashboard(slug?: string)` to `frontend/app/lib/serverApi.ts` using
      `serverFetch`

## 5. Frontend — Components

- [x] 5.1 Create `frontend/app/owner/marketing/VariantTable.tsx` — columns: Variant, Weight,
      Active, Page Views, Bookings, Conversion %
- [x] 5.2 Create `frontend/app/owner/marketing/ExperimentStatusBadge.tsx` — active/paused/none pill
- [x] 5.3 Create an empty-state panel (e.g. "Marketing tracking isn't available yet") rendered
      when `available === false`

## 6. Frontend — Page

- [x] 6.1 Create `frontend/app/owner/marketing/page.tsx` (server component): call
      `serverApi.getMe()` + `serverApi.getMarketingDashboard()`, redirect to `/reports` if
      `me.role !== 'OWNER'`
- [x] 6.2 Compose `ExperimentStatusBadge` + `VariantTable`/empty-state on the page

## 7. Frontend — Navigation link

- [x] 7.1 Add `navMarketing` i18n key (EN/RU) to `frontend/app/lib/i18n.ts`
- [x] 7.2 In `frontend/app/components/AdminMenu.tsx`, add a link to `/owner/marketing` in the
      OWNER branch of `linksFor()`
