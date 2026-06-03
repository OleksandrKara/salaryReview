# salaryReview

A biweekly commission calculator for a small nail salon. Replaces a manual
spreadsheet: enter a handful of numbers per provider per pay period (card total,
cash total, card tips, adjustments, commission %) and get back the exact
WhatsApp-ready settlement message — *"Zelle AK to Anna: $284.55 / Cash from
Anna to AK: $160.05"*.

This is a small but real full-stack app (Spring Boot + Next.js + Postgres in
Docker). It's intentionally tight: one calculator, one editor page, one admin
page, two test layers.

---

## Quickstart — clone and run

Prereqs: **Docker Desktop** (or any Docker engine + Compose v2). Nothing else.

```bash
git clone <this-repo> salaryReview
cd salaryReview
docker compose up --build
```

First build pulls images and compiles Maven + Next.js (~2–3 min). Subsequent
runs are ~10 s.

Then open:

| | URL |
|---|---|
| **App** | http://localhost:3000 |
| Backend REST | http://localhost:8080 |
| Adminer (DB browser) | http://localhost:8081 — server `postgres`, db/user/pass `salonreview` / `salon` / `salon` |
| Postgres | `localhost:5432` (db `salonreview`, user/pass `salon`/`salon`) |

To stop: `docker compose down`. To wipe the data volume too: `docker compose down -v`.

For the production VPS setup (nginx + TLS, firewall, security hardening), see
[`docs/DEPLOY.md`](docs/DEPLOY.md).

---

## What you'll see

1. **Home (`/`)** — list of pay periods (newest first), per-row delete (with
   confirmation), create-period form, and a link to provider admin.
2. **Period editor (`/periods/{id}`)** — one editable row per active provider:
   `# procedures`, `card $`, `cash $`, `card tips $`, `adjustments $ + note`,
   and an optional per-period `rate %` (falls back to the provider's default).
   Edits PUT on blur. **Calculate** renders WhatsApp-ready settlement cards with
   a Copy button. Header has a **Delete period** button.
3. **Providers admin (`/providers`)** — list/create/inline-edit every field of
   every provider, soft-delete via the active checkbox, hard-delete via the ✕
   (cascades to historical entries with a strong confirmation).

Seeded data: provider **Anna** (rate 45%) with her real 1-15 May 2026 entry, and
provider **Bea** (rate 50%). Open `/periods/1` to see them.

---

## Architecture

```
┌──────────────────┐   browser fetch (CORS)   ┌──────────────────┐
│  Next.js 16      │ ────────────────────────▶│  Spring Boot 4   │
│  (frontend)      │                          │  (backend)       │
│  :3000           │ ◀──────────────────────  │  :8080           │
└──────────────────┘                          └────────┬─────────┘
                                                       │ JDBC
                                                       ▼
                                              ┌──────────────────┐
                                              │  PostgreSQL 16   │
                                              │  + Flyway        │
                                              │  :5432           │
                                              └──────────────────┘

(Plus an Adminer container on :8081 for browsing the DB.)
```

**Calculation kernel (`backend/.../service/CommissionCalculator.java`):**

```
effective_rate    = entry.commissionRate ?? provider.commissionRate
tips_after_fee    = round2(card_tips * (1 - card_tip_fee_rate))
zelle_to_provider = round2(card_total * effective_rate + tips_after_fee + adjustments)
cash_to_salon     = round2(cash_total * (1 - effective_rate))
```

All money is `BigDecimal` with `HALF_UP` rounding at scale 2 — never `double`.

---

## Tech stack

**Backend** (`backend/`)
- Java 21, Spring Boot 4.0.6, Maven
- Spring Web MVC, Spring Data JPA + Hibernate, Spring Validation
- Flyway (3 migrations: schema, seed, per-entry rate override)
- PostgreSQL JDBC driver, Lombok, Actuator

**Frontend** (`frontend/`)
- Next.js 16.2.6 (App Router) + React 19 + TypeScript
- Tailwind CSS v4
- Server components for data fetching, client components for editing

**Infra**
- PostgreSQL 16, Adminer
- Multistage Dockerfiles (backend: ~200 MB, frontend: ~250 MB via Next standalone output)
- `docker-compose.yml` orchestrates 4 services with healthcheck-gated startup

---

## REST API

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/providers` | `?all=true` to include inactive |
| `POST` | `/api/providers` | `{name, displayName, commissionRate?, cardTipFeeRate?}` |
| `PATCH` | `/api/providers/{id}` | any subset of `{name, displayName, commissionRate, cardTipFeeRate, active}` |
| `DELETE` | `/api/providers/{id}` | cascades to entries — use `active=false` for soft delete |
| `GET` | `/api/pay-periods` | newest first |
| `POST` | `/api/pay-periods` | `{year, month, half}` — label auto-generated |
| `GET` | `/api/pay-periods/{id}` | period + embedded entries |
| `DELETE` | `/api/pay-periods/{id}` | cascades to entries |
| `PUT` | `/api/pay-periods/{id}/entries/{providerId}` | upsert; body has the 4 numbers + note + optional `commissionRate` |
| `GET` | `/api/pay-periods/{id}/settlements` | calculated payouts + formatted message text |

Validation errors return `400` with field-level detail. Missing resources
return `404`. See `web/GlobalExceptionHandler.java`.

### Curl examples

```bash
# List providers
curl -s localhost:8080/api/providers | jq

# Add a provider
curl -s -X POST localhost:8080/api/providers \
  -H 'Content-Type: application/json' \
  -d '{"name":"Dana Lastname","displayName":"Dana","commissionRate":0.50}' | jq

# Compute settlements for period 1
curl -s localhost:8080/api/pay-periods/1/settlements | jq

# Just the formatted message
curl -s localhost:8080/api/pay-periods/1/settlements | jq -r '.[0].messageText'
```

---

## Running tests

```bash
cd backend
./mvnw test
```

7 tests, ~15 s total:

| Test class | What it covers |
|---|---|
| `CommissionCalculatorTest` (×4) | Pure unit tests: happy path against real data → `zelle=$284.55`, all zeros, negative adjustment, per-entry rate override |
| `SettlementControllerTest` (×2) | MockMvc integration: GET `/settlements` against seeded data; 404 path |
| `SalonreviewApplicationTests` | Spring context-load smoke test |

The integration test currently runs against the live Postgres in
docker-compose (i.e. `docker compose up -d postgres` must be running). It was
written for Testcontainers first, but Testcontainers 1.21.x doesn't talk
cleanly to Docker Desktop 29.x on this host — see the inline note at the top of
`SettlementControllerTest.java`. Switching back is a 2-line annotation change
once that version mismatch resolves.

---

## Development without Docker

If you'd rather run the backend / frontend on your host (faster reloads):

```bash
# 1) Postgres only in Docker
docker compose up -d postgres

# 2) Backend on host (Java 21 required)
cd backend && ./mvnw spring-boot:run

# 3) Frontend on host (Node 22+ required)
cd frontend && npm install && npm run dev
```

`http://localhost:3000` still hits the host backend at `:8080`.

---

## Project layout

```
salaryReview/
├── README.md
├── docker-compose.yml
├── backend/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/salonreview/
│       │   ├── domain/       Provider, PayPeriod, PeriodEntry, SalonConfig, Half
│       │   ├── repo/         Spring Data repositories
│       │   ├── service/      CommissionCalculator, SettlementService, MessageFormatter
│       │   ├── web/          REST controllers + GlobalExceptionHandler + DTOs
│       │   └── config/       WebConfig (CORS)
│       ├── main/resources/
│       │   ├── application.yml
│       │   └── db/migration/   V1__init_schema, V2__seed_demo_data, V3__entry_commission_rate
│       └── test/java/com/salonreview/
│           ├── service/CommissionCalculatorTest.java
│           └── web/SettlementControllerTest.java
└── frontend/
    ├── Dockerfile
    ├── next.config.ts        (output: "standalone")
    └── app/
        ├── page.tsx            (home: period list + create form)
        ├── PeriodRow.tsx       (delete-with-confirm row)
        ├── CreatePeriodForm.tsx
        ├── lib/
        │   ├── api.ts          (typed apiFetch<T> wrapper)
        │   └── types.ts        (TS mirror of backend DTOs)
        ├── periods/[id]/
        │   ├── page.tsx        (server component — fetch period + providers)
        │   ├── PeriodEditor.tsx       (client — table + Calculate + copy)
        │   └── DeletePeriodButton.tsx
        └── providers/
            ├── page.tsx
            └── ProvidersManager.tsx
```

---

## Design notes worth calling out

- **DTOs everywhere on the wire.** JPA entities never escape the service
  layer. With `spring.jpa.open-in-view: false`, returning entities directly
  would otherwise blow up on lazy proxies — and you'd be leaking persistence
  shape to clients anyway.
- **Soft delete vs. hard delete are both first-class.** Toggling `active=false`
  keeps history; `DELETE` cascades through entries via the FK. The UI exposes
  both with appropriate friction (checkbox vs. confirm dialog).
- **`output: "standalone"` for the frontend image.** The runner stage copies
  only the standalone `server.js` + the modules it actually needs, instead of
  the full `node_modules`. Image shrinks dramatically.
- **Typed `apiFetch<T>` wrapper.** Earlier code chained `.then(jsonOrThrow)`
  which TypeScript widened to `unknown` under strict mode — Turbopack dev
  hides this; `next build` catches it. Generic bound at the call site.
- **BigDecimal HALF_UP at scale 2.** No `double`. Money rounding is a known
  footgun for tools like this.
- **Square reads are cached briefly, with an honest timestamp + manual Sync.**
  Settlement views read Square live (read‑only) but cache it for speed; the
  "synced" badge shows the real last‑fetch time and a Sync button forces a fresh
  pull. Full details — TTLs, the Sync endpoint, and session lifetime — in
  [`docs/CACHING.md`](docs/CACHING.md).

---

## What's not in MVP (planned next)

- **Square API integration.** Pull `card_total`, `cash_total`, `card_tips`,
  and procedure count automatically per pay period — replaces most of the
  manual data entry.
- **Typed `Adjustment` table.** Today's lump-sum + free-text note splits into a
  child table with categories (REDO, REFUND_COVER, DISCOUNT_COVER, CANCELLATION_FEE).
- **Authentication.** Currently anonymous on a local network.
- **Historical reporting.** Aggregate views across many periods.

---

## License

Internal / personal project.
