# End-to-end tests (Playwright)

Phase 6.5 (`openspec/changes/multi-tenant-salon-platform`). **Not wired into GitHub Actions** —
this app's CI/deploy pipeline drives a live production deploy, and this repo's own history has
already hit real CI-reliability friction once; a new, timing-sensitive multi-service e2e stage
isn't something to bolt onto that pipeline without separate review. Run these locally, against an
environment you stand up yourself.

## What's covered

- `business-switcher.spec.ts` — design.md D12: the AdminMenu switcher renders as plain text for a
  single-membership user (no visual change) and as a real `<select>` for a platform_admin with
  access to more than one business; switching actually changes which business's data you see.
- `onboarding.spec.ts` — the Phase 6.4 `/onboarding` page: connect Square (sandbox), then invite a
  manager. **Skips itself** (doesn't fail) when `SQUARE_SANDBOX_ACCESS_TOKEN`/
  `SQUARE_SANDBOX_LOCATION_ID` aren't set — there's no way to exercise a real, successful Square
  connection without real sandbox credentials, and this repo doesn't ship any.

## Setup: a fresh, isolated instance

These tests create real accounts/businesses via the platform-admin API and expect a **fresh**
instance (only the bootstrap owner exists) — run them against a throwaway environment, never a
real backup or production. Mirrors the manual pattern used throughout this session's own PR
verifications:

```bash
# 1. Fresh Postgres
docker run -d --name e2e-pg -e POSTGRES_USER=salon -e POSTGRES_PASSWORD=salon \
  -e POSTGRES_DB=salonreview -p 55440:5432 pgvector/pgvector:pg16

# 2. Backend, from backend/
SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:55440/salonreview" \
SPRING_DATASOURCE_USERNAME=salon SPRING_DATASOURCE_PASSWORD=salon \
SQUARE_CREDENTIALS_MASTER_KEY="$(openssl rand -base64 32)" \
RAG_ENABLED=false \
APP_OWNER_USERNAME=owner APP_OWNER_PASSWORD=e2e-bootstrap-password \
TWILIO_AUTH_TOKEN=throwaway \
SERVER_PORT=18080 \
./mvnw -q spring-boot:run

# 3. Frontend, from frontend/ (this directory's parent)
BACKEND_URL="http://localhost:18080" npm run dev -- --port 13000
```

## Running

```bash
cd frontend
E2E_OWNER_USERNAME=owner E2E_OWNER_PASSWORD=e2e-bootstrap-password \
E2E_BASE_URL=http://localhost:13000 \
npx playwright test
```

To also run the onboarding spec instead of skipping it, add real Square sandbox credentials:

```bash
SQUARE_SANDBOX_ACCESS_TOKEN=... SQUARE_SANDBOX_LOCATION_ID=... npx playwright test onboarding
```

Each spec file creates its own second business with a random suffix (`randomSuffix()` in
`fixtures.ts`), so re-running against the same still-up instance doesn't collide — but the
instance itself should still be thrown away afterward, same as any other isolated-env
verification in this codebase (never a real backup, never left running against real data).
