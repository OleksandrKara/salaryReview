# salaryReview

A real, in-production payroll engine for a nail salons. It replaces a manual
spreadsheet by reading the salon's **Square** account (bookings, orders, catalog,
team members) live and computing exact commission payouts — *"Zelle AK to Anna:
$284.55 / Cash from Anna to AK: $160.05"* — with no manual data entry.

It started as a calculator and grew into a real full-stack system: a
tiered no-clawback commission engine, a Square reconciliation pipeline validated
against real transaction data, role-based accounts for the owner/manager/providers,
fraud-style anomaly detection, and an **LLM-powered triage assistant** that
explains flagged bookings to the owner in plain language.

---

## AI feature: LLM-assisted suspicious-booking triage

The detector flags bookings that happened but have no payment trail (no Square
checkout, no cash note, customer isn't a comped owner/family visit). Historically
the owner had to manually investigate each one. Now they can click **Explain** and
get a structured, cited judgment from **Claude (Haiku 4.5)**:

- **Structured outputs, not prompt-and-parse.** The Anthropic Java SDK derives a
  JSON Schema straight from the `TriageResult` record and constrains the model to
  it — classification (`LIKELY_LEGIT` / `NEEDS_REVIEW` / `LIKELY_FRAUD`),
  confidence, a cited explanation, a ready-to-send draft message, and the named
  detection signals it relied on. No brittle JSON parsing on our side.
- **Prompt caching.** The system prompt (rubric + signal taxonomy + few-shot
  examples) is large by design — large enough to clear the model's cacheable-prefix
  minimum — and is sent with `cache_control: ephemeral`, cutting input cost on
  repeat calls.
- **Versioned, evaluable prompts.** Prompts live in code (`TriagePrompts.java`),
  not a dashboard. Each version is tagged (`v1`, `v2`, …); old versions are kept
  for regression evals against historically labeled bookings before a prompt
  change ships.
- **LangSmith tracing + human-in-the-loop feedback.** Every call ships an async
  trace (LLM never blocked by tracing I/O); when the owner thumbs-up/down a
  triage, that feedback posts back to the same trace — a real eval/feedback loop,
  not just logging.
- **Safety-conscious by construction.** Model refusals are caught and degrade to a
  safe `NEEDS_REVIEW` rather than surfacing an error; the feature is off by
  default behind a flag and never runs as a batch job — only on an explicit owner
  click.
- **Cached results, not repeated spend.** The first triage per booking is
  persisted; clicking again returns the stored verdict instantly with zero LLM
  calls, until the prompt version changes.

See `backend/src/main/java/com/salonreview/ai/` and
`openspec/changes/suspicious-booking-ai-triage/design.md` for the full design.

---

## Other things worth knowing about

- **No-clawback tiered commission engine.** Providers earn a higher rate
  (e.g. 50% vs. a 45% base) once they cross a monthly service-count threshold —
  but pay periods are half-months, so the tier can't be known until the month
  closes. Rather than overpay-then-clawback, the **first half always pays at base
  rate**; at month close the whole month is reconciled and the uplift is paid as a
  bonus. The provider is never overpaid and never dragged into owing money back.
- **Square reconciliation, validated on real data.** Square's bookings give
  attribution (who/what/when); its orders give the money but no provider. The
  aggregator joins them on customer + service + day to recover full attribution —
  verified at 100% match against a real month of salon data before automating it
  (see `docs/ROADMAP.md`). Handles cash-note parsing (English `cashew $nn` and
  Russian `наличные`), prepaid package draw-downs with anti-fraud guards (a
  draw-down only ever confirms against a real Square booking that happened),
  redo/comp/manual-credit adjustments, no-show fee detection, and owner/family
  comped visits — all folded into one settlement.
- **Real accounts and roles**, not a shared login: owner (full admin + user
  management), manager (full reports, no user admin), provider (read-only view of
  their own pay period + approve/request-correction). Spring Security server
  sessions, BCrypt-hashed passwords, `@PreAuthorize`-gated endpoints.
- **Caching with an honest freshness story.** Square reads are cached briefly
  (TTL'd per how often each thing actually changes) so a report view that took
  7.5s cold renders in ~0.1s on a cache hit — but the UI shows the *real* last-Square-fetch
  time, never a misleading "just now," and a Sync button forces a fresh
  pull on demand. Independent Square reads (bookings, orders) run concurrently,
  roughly halving cold-load latency. Full details in
  [`docs/CACHING.md`](docs/CACHING.md).
- **Revenue forecasting** that blends two independent signals — historical
  first-half/second-half split ratios and learned bias on a booking-ceiling
  estimate — and widens its predicted range when the two disagree, rather than
  presenting false precision.
- **BigDecimal everywhere, HALF_UP at scale 2.** No `double` touches money,
  anywhere in the codebase.

---

## Quickstart — clone and run

Prereqs: **Docker Desktop** (or any Docker engine + Compose v2). Nothing else.

```bash
git clone https://github.com/OleksandrKara/salaryReview.git
cd salaryReview
cp .env.example .env   # fill in Square + (optionally) Anthropic/LangSmith keys
docker compose up --build
```

Then open:

| | URL |
|---|---|
| **App** | http://localhost:3000 |
| Backend REST | http://localhost:8080 |
| Adminer (DB browser) | http://localhost:8081 — server `postgres`, db/user/pass `salonreview` / `salon` / `salon` |
| Postgres | `localhost:5432` (db `salonreview`, user/pass `salon`/`salon`) |

To stop: `docker compose down`. To wipe the data volume too: `docker compose down -v`.

Square credentials are required for live data (sandbox keys work fine for a
demo — `SQUARE_ENVIRONMENT=sandbox` in `.env.example`). The AI triage feature
needs `ANTHROPIC_API_KEY` and `AI_TRIAGE_ENABLED=true`; `LANGSMITH_API_KEY` is
optional and only adds tracing.

For the production VPS setup (nginx + TLS, firewall, security hardening), see
[`docs/DEPLOY.md`](docs/DEPLOY.md). There's no staging environment — only local
Docker Compose for development and the one production VPS.

---

## What you'll see

- **`/reports`** (owner/manager) — month view per provider: tier badge, payout
  breakdown (card/cash/tips/bonus), inline tier grant/revoke, suspicious-booking
  list with the AI **Explain** button, no-show fee panel, revenue chart + pulse +
  forecast.
- **`/me`** (provider) — a provider's own read-only month, with
  approve/request-correction feedback that surfaces back to the owner/manager view.
- **`/owner/overview`** — cross-month KPI and growth view.
- **`/admin/*`** — users, redos, manual credits, owner-customers (comped
  family/friends), prepaid packages.

---

## Architecture

```
┌──────────────────┐   same-origin /api/* proxy  ┌──────────────────┐      ┌─────────────────┐
│  Next.js 16      │ ───────────────────────────▶│  Spring Boot 4   │─────▶│  Square API       │
│  (frontend)      │ ◀───────────────────────────│  (backend)       │◀─────│  (read-only)      │
│  :3000           │   forwards session cookie    │  :8080           │      └─────────────────┘
└──────────────────┘                              └────────┬─────────┘      ┌─────────────────┐
                                                            │ JDBC          │  Anthropic API     │
                                                            ▼               │  (Claude Haiku 4.5) │
                                                   ┌──────────────────┐     └─────────────────┘
                                                   │  PostgreSQL 16   │     ┌─────────────────┐
                                                   │  + Flyway        │     │  LangSmith         │
                                                   │  (21 migrations) │     │  (trace/eval)       │
                                                   └──────────────────┘     └─────────────────┘
```

The browser never talks directly to the backend — the Next.js server proxies
`/api/*` and forwards the auth cookie, so no backend URL or API base is exposed
to the client.

**Commission kernel** (`backend/.../commission/TierCommissionEngine.java`):

```
effective_rate (first half)  = baseRate                              // always provisional
effective_rate (month close) = qualified ? tierRate : baseRate       // reconciled
tier_bonus       = qualified ? monthCardRevenue * tierUplift : 0     // paid once, at close
cash_tier_rebate = qualified ? monthCashGross   * tierUplift : 0
zelle_to_provider = round2(cardRevenue * rate + tipsAfterCardFee + adjustments + tierBonus)
cash_to_salon      = round2(cashCollected - cashGross * rate - cashTierRebate)
```

---

## Tech stack

**Backend** (`backend/`)
- Java 21, Spring Boot 4.0.6, Maven
- Spring Web MVC, Spring Data JPA + Hibernate, Spring Security (server sessions,
  BCrypt, method security), Spring Validation
- Flyway — 21 migrations tracking the full feature history (tiers, accounts,
  prepaid packages, redos, manual credits, suspicious-booking triage, …)
- **Anthropic Java SDK** (`anthropic-java` 2.40.1) — Claude Haiku 4.5 with
  structured outputs + prompt caching
- **LangSmith** REST tracing for LLM observability and feedback-driven evals
- PostgreSQL JDBC driver, Lombok, Actuator

**Frontend** (`frontend/`)
- Next.js 16.2.6 (App Router) + React 19 + TypeScript
- Tailwind CSS v4
- Server components for data fetching; thin `/api/*` route proxies forward the
  session cookie to the backend — no API base ever shipped to the browser

**Infra**
- PostgreSQL 16, Adminer
- Multistage Dockerfiles (Java 21 / Node 22, Next `standalone` output)
- `docker-compose.yml`: 4 services, healthcheck-gated startup, loopback-only
  ports behind an nginx + TLS reverse proxy in production

---

## Testing

```bash
cd backend && ./mvnw test     # 23 test classes — unit + MockMvc integration
npx playwright test           # e2e, against chromium/firefox/webkit
```

Backend coverage spans the commission engine, the Square reconciliation
pipeline (checkout attribution, tip allocation, cash-note parsing, prepaid
draw-downs, redos, no-show fees, owner comps), the suspicious-booking detector,
and the AI triage service (mocking the Anthropic call to test caching, refusal
handling, and feedback recording without hitting the real API).

---

## REST API surface

Role-gated via Spring Security (`OWNER`, `MANAGER`, `PROVIDER`):

| Area | Example paths |
|---|---|
| Auth | `POST /api/login`, `POST /api/logout`, `GET /api/me` |
| Settlements | `GET /api/settlements/preview`, `GET /api/settlements/me`, `GET /api/settlements/me/detail` |
| Providers | `GET/POST/PATCH/DELETE /api/providers` |
| Suspicious bookings + AI | `GET /api/suspicious`, `POST /api/suspicious/{bookingId}/triage`, `POST /api/suspicious/{bookingId}/triage/feedback` |
| Tier grants | `GET/POST /api/settlements/grants` |
| Redos / manual credits / owner-customers / prepaid | `/api/redos`, `/api/manual-credits`, `/api/owner-customers`, `/api/prepaid` |
| No-show fees | `/api/no-show-fees` |
| Square sync | `POST /api/sync` (busts the read cache on demand) |
| Users (owner-only) | `/api/users` |

Validation errors return `400` with field-level detail; missing resources `404`;
LLM failures `502`. See `web/GlobalExceptionHandler.java` and
`web/TriageExceptionHandler.java`.

---

## Project layout

```
salaryReview/
├── README.md
├── docker-compose.yml
├── docs/                  DEPLOY.md, CACHING.md, ROADMAP.md
├── openspec/              spec-driven change proposals (design docs, deltas)
├── backend/
│   └── src/main/java/com/salonreview/
│       ├── ai/            Anthropic + LangSmith triage service, prompts, structured outputs
│       ├── commission/    TierCommissionEngine — the no-clawback kernel
│       ├── square/        Square client + reconciliation, redos, prepaid, no-show, forecasting
│       ├── domain/        JPA entities
│       ├── repo/          Spring Data repositories
│       ├── service/       legacy manual-entry calculator (pre-Square)
│       ├── web/           REST controllers + DTOs
│       └── config/        security, Square + AI properties, owner bootstrap
├── frontend/app/
│   ├── reports/[providerId]/   owner/manager month view + AI Explain button
│   ├── me/                     provider self-service view
│   ├── owner/overview/         cross-month KPIs
│   └── admin/                  users, redos, manual credits, owner-customers, prepaid
└── e2e/tests/             Playwright specs (chromium/firefox/webkit)
```

---

## Roadmap

Square OAuth + multi-tenant isolation (per-merchant credentials instead of a
single personal access token) and Stripe billing are the next major phases, en
route to a Square App Marketplace listing. Full detail in
[`docs/ROADMAP.md`](docs/ROADMAP.md).

---

## License

Internal / personal project.
