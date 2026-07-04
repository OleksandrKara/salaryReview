## Context

`salonLandings` is a separate FastAPI + Vite/React service (mani.akluxnails.com) that already
writes page-view/submission tracking into a `marketing` Postgres schema on this same DB instance
(`salonreview`, user `salon`), and has just gained an experimentation layer: `landing_pages`,
`landing_variants`, `experiments`, `events`, and `attribution` tables, all created by that
service's own idempotent SQL migration runner (not Flyway) at its own startup. This app
(`salaryReview`) does not own, and must never own, that schema — but the owner wants to see its
results without leaving the Operations Portal.

## Goals / Non-Goals

**Goals:**
- Read variant performance (page views, bookings, conversion rate) and experiment status from the
  `marketing` schema, read-only.
- Never let this schema's absence, partial migration state, or unavailability affect Operations
  Portal's own health or startup.
- Reuse the existing `/api/owner/**` security rule and `serverApi`/`forwardToBackend` proxy
  conventions rather than inventing new ones.

**Non-Goals:**
- No Flyway migration for `marketing.*` tables — this app must not become the owner or co-owner
  of that schema's DDL.
- No write operations against `marketing.*`.
- No experiment configuration UI.

## Decisions

### D1: Plain `JdbcTemplate`, not a JPA `@Entity`, for the `marketing` schema

**Decision**: `MarketingDashboardRepository` is a plain Spring `JdbcTemplate`-based class
(constructor-injected `JdbcTemplate`, which Spring Boot autoconfigures transitively from
`spring-boot-starter-data-jpa` whenever a `DataSource` is present — no new dependency). It issues
hand-written SQL against `marketing.landing_pages`, `landing_variants`, `experiments`, `events`,
and `attribution`, and is never involved in schema validation.

**Rationale**: This app runs with `spring.jpa.hibernate.ddl-auto: validate` — Hibernate validates
every mapped `@Entity` against the schema at startup and refuses to start on a mismatch. If any of
the `marketing.*` tables were mapped as JPA entities, Operations Portal's own startup health would
become coupled to a schema owned and evolved independently by the `salonLandings` service — for
example, if that service hasn't run its migrations yet, or has evolved the schema in a way this
app's entity mapping doesn't expect, this app would fail to boot. That is unacceptable for a
stable production system per this repo's own constraints. Plain JDBC has no such coupling: it only
touches the schema at request time, in a single method, and any mismatch is caught locally.

**Alternatives considered**:
- *JPA `@Entity` mapped to `@Table(schema = "marketing", ...)`*: Rejected — couples this app's
  startup validation to another service's schema lifecycle, and to which of the two apps happens
  to start first.
- *HTTP call to the `salonLandings` FastAPI service instead of direct DB access*: Rejected as
  unnecessary complexity — both apps already share the same Postgres instance and the `salon` DB
  user already has read access to `marketing.*`; adding a network dependency and its own failure
  modes (timeouts, CORS, auth) buys nothing here.

### D2: Resilience — catch `DataAccessException`, return an "unavailable" DTO

**Decision**: Every `MarketingDashboardRepository` query is wrapped by
`MarketingDashboardService` in a catch for `org.springframework.dao.DataAccessException` (which
covers `BadSqlGrammarException`, thrown when the `marketing` schema or a table doesn't exist yet).
On any such failure, or when the requested `slug` has no matching `landing_pages` row, the service
returns `MarketingDashboardDto.unavailable()` — an explicit empty-state payload — rather than
propagating the exception. The controller never surfaces a 500 for this reason.

**Rationale**: The two services' deployments and migrations are independent. Operations Portal
must render a sensible empty state (not crash, not 500) if `salonLandings` hasn't run yet, is
mid-deploy, or the schema is temporarily unreachable.

### D3: Conversion rate is computed, not stored

**Decision**: `conversionRate = pageViews == 0 ? 0 : bookingsCompleted / (double) pageViews`,
computed in `MarketingDashboardService` from raw counts returned by the repository. No new column,
no writeback.

### D4: Security — no new `SecurityConfig` rule needed

**Decision**: The controller is mounted at `/api/owner/marketing`, which the existing
`.requestMatchers("/api/users/**", "/api/owner/**", "/api/rag/admin/**").hasRole("OWNER")` matcher
in `SecurityConfig.java` already covers. Add a one-line comment there noting it; do not add a
redundant matcher. The `/owner/marketing` frontend page checks `me.role === 'OWNER'` server-side
and redirects to `/reports` otherwise, matching `/owner/overview`.

**No new Flyway migration required** — this change adds zero tables/columns to this app's own
schema.

## Risks / Trade-offs

- **No seed/admin UI for `marketing.landing_pages`/`landing_variants`/`experiments` yet** (owned
  by `salonLandings`) — until that service grows one, rows must be seeded by hand via SQL. Not
  addressed by this change; called out as a known gap.
- **Cross-schema query correctness depends on the `salon` DB user having SELECT on `marketing.*`**
  — true today since both services share one Postgres role; if that ever changes, this dashboard
  degrades to the unavailable state (D2) rather than failing loudly, which is an acceptable
  trade-off for a read-only, non-critical dashboard.
- **No caching** — each page load re-queries `marketing.*` directly. Acceptable at this scale (one
  owner, one landing page, low traffic); revisit if query volume grows.

## Open Questions

- Should MANAGER role also see this page? Scoped to OWNER only for now, consistent with most
  other admin/analytics pages in this app (retention is the one exception, and that's explicitly
  called out in `SecurityConfig`).
