## Context

Today: `docker-compose.yml` reads secrets from a gitignored `.env` on the VPS and injects them as container **environment variables** (`SQUARE_ACCESS_TOKEN`, `ANTHROPIC_API_KEY`, `VOYAGE_API_KEY`, `LANGSMITH_API_KEY`, `POSTGRES_PASSWORD`, `APP_OWNER_PASSWORD`). Deployment is a GitHub Actions job that SSHes to the VPS (using GH-secret `VPS_SSH_KEY` etc.), `git reset --hard origin/master`, and `docker compose up -d --build`. The `.env` is never committed and not baked into images. The repo is **public**; the Square token reads customer PII.

The weaknesses this change targets: secrets visible via `docker inspect` / `/proc/<pid>/environ`, and a cleartext `.env` on disk.

## Goals / Non-Goals

**Goals**
- Secrets are delivered to containers as **files, not env vars** (off `docker inspect`).
- Secrets stored **encrypted at rest** in the swarm raft store.
- **Zero extra recurring steps**: automated deploy stays one push; local dev keeps `docker compose up`; no per-restart unlock.
- A genuine, idiomatic Docker Swarm secrets setup (educational).

**Non-Goals**
- A managed secrets manager (Vault/Doppler/Infisical) — overkill for one VPS.
- `--autolock` (adds a recurring unlock step; can be enabled later deliberately).
- Multi-node swarm or an image registry.
- Rotating the actual secret values, or the public-repo controls (branch protection, least-privilege Square token) — complementary, tracked separately.

## Decisions

**D1 — Swarm secrets over plain compose `secrets:` or a vault.** Plain compose `secrets:` (non-swarm) would get secrets off env vars but **not** encrypt at rest. A vault is too much for one host. Swarm gives both encryption-at-rest and file-based delivery with vanilla Docker — and is the stated learning goal.

**D2 — Do NOT enable `--autolock`.** Autolock is the only Swarm-secrets feature that creates a *recurring* step (a passphrase prompt on every daemon restart — reboot/upgrade — or services won't start). Leaving it off honors "no extra steps." Consequence: encryption-at-rest is "raft key on disk" strength (a host compromise still reads everything), which is acceptable and still strictly better than a plaintext `.env`. Autolock can be turned on later as a deliberate exercise.

**D3 — Backend reads file-based secrets via Spring `configtree:`.** Spring Boot doesn't honor `*_FILE` env conventions, but `spring.config.import=optional:configtree:/run/secrets/` maps each file in `/run/secrets/` to a property by filename (e.g. file `square.access-token` → property `square.access-token`). So secret **files are named after the property paths** they feed, and no Java changes are needed. Postgres uses its native `POSTGRES_PASSWORD_FILE`. *Alternative — an entrypoint that reads files and exports env vars — rejected: it puts secrets back into the environment, defeating the point.*

**D4 — One compose file for both local and prod.** The file declares top-level `secrets:` with `file:` sources and `image:` tags on built services. `docker compose up` (local) bind-mounts the files into `/run/secrets/`; `docker stack deploy` (prod) creates real swarm secrets from the same `file:` sources. Build keys (`build:`, `depends_on`) are used by compose and ignored by stack deploy; `deploy:` (restart policy, replicas=1) is used by swarm and ignored by compose. Keeps local dev and prod on a single source of truth. *Alternative — a separate `docker-stack.yml` — rejected to avoid drift.*

**D5 — Build locally, then `stack deploy`; no registry.** `docker stack deploy` does not build. On a **single-node** swarm the built image lives in the same daemon's local store, so services run it by `image:` name without a registry. The deploy script becomes `docker compose build` then `docker stack deploy -c docker-compose.yml salonreview`. A real cluster would need a registry — explicitly out of scope.

**D6 — Secret set and naming.** Move only the *sensitive* values: `square.access-token`, `anthropic.api-key` (map to whatever property/env the Anthropic client reads), `rag.voyage-api-key`, `langsmith.api-key`, `postgres_password`, `app.owner-password`. Non-secret config (`RAG_ENABLED`, `SQUARE_ENVIRONMENT`, `SQUARE_LOCATION_ID`, feature flags) stays in compose env/`.env`. Exact property↔file names are pinned during implementation by reading how each is currently bound.

## Risks / Trade-offs

- **Swarm secrets are immutable** → rotation = new file content → `stack deploy` creates a new secret version and updates the service; occasionally needs a manual `docker secret rm` of the old one. Rare here; documented in `DEPLOY.md`. *Mitigation: write the rotation steps down.*
- **`configtree` filename↔property mismatch** → the app silently reads an empty key and a feature breaks at runtime. *Mitigation: verify each property resolves on boot (log a redacted "configured: true/false" per key) before cutover; a CI/contextLoads smoke test with dummy secret files.*
- **`stack deploy` ignores some compose keys** (`depends_on` conditions, `build`) → startup ordering differs from compose. *Mitigation: rely on the backend's existing DB-retry/health check rather than `depends_on` gating; verify health on deploy.*
- **No autolock** → weaker at-rest guarantee (accepted in D2).
- **Single-node, locally-built images** → fine for one VPS; would break on multi-node (out of scope).
- **Cutover risk** → first `stack deploy` is a new path. *Mitigation: rehearse locally; keep the `compose up` path revertible (see Rollback).*

## Migration Plan

1. **Code (one PR):** add `secrets:` + `image:` tags + `deploy:` to `docker-compose.yml`; Postgres `POSTGRES_PASSWORD_FILE`; backend `application.yml` `configtree` import; `DEPLOY.md`; update the deploy workflow to build + `stack deploy`.
2. **VPS (one-time, operator):** `docker swarm init`; create `secrets/` with one `chmod 600` file per secret (values copied from the current `.env`); trim those keys out of `.env`.
3. **Cutover:** run a manual `docker compose build` + `docker stack deploy` once; verify health, `docker inspect` shows no secret env, Square/RAG/KB-SOP work.
4. **Enable the automated path:** merge; the next push to master deploys via the new workflow.

**Rollback:** revert the workflow to `docker compose up -d --build` and restore the trimmed values into `.env`; `docker stack rm salonreview` and `docker swarm leave --force` if abandoning swarm. The application code is unaffected (configtree import is `optional:`, so env vars still bind if present).

## Open Questions

- Final list of secrets and their exact property/file names (pin by reading current bindings during implementation).
- Enable `--autolock` later as a follow-up exercise, accepting the per-restart unlock?
- Should non-secret config (feature flags, `SQUARE_ENVIRONMENT`) also move to swarm `configs` for consistency, or stay in `.env`? (Leaning: stay in `.env`.)
- Document a key-rotation runbook now, or when first needed?
