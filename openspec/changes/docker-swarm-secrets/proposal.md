## Why

Production secrets — the **Square access token (reads customer PII)**, Anthropic/Voyage/LangSmith keys, and the Postgres + app-owner passwords — currently live as a **plaintext `.env` on the VPS** and are injected into containers as **environment variables**. Env-var secrets are readable by anything that can run `docker inspect` or read `/proc/<pid>/environ` (another process, a partially-compromised container, a misconfigured log/monitoring agent), and the `.env` sits in cleartext on disk. The repo is **public**, which raises the stakes on getting secret handling right.

Moving secrets to **Docker Swarm secrets** takes them **off environment variables** (mounted as files in `/run/secrets/`, tmpfs) and stores them **encrypted in the swarm raft log** — a meaningful hardening of the runtime, and a deliberate learning exercise in Docker secrets.

The catch is that Swarm changes the orchestration model (`docker stack deploy` instead of `docker compose up`) and requires the app to read secrets from files. This change is designed so that **day-to-day work has zero extra steps**: the deploy stays automated (push to master), local dev keeps using `docker compose up`, and the one recurring-friction feature (`--autolock`) is intentionally left off.

## What Changes

- The VPS runs as a **single-node Docker Swarm** (`docker swarm init`); production deploys via **`docker stack deploy`**.
- Sensitive values move from `.env` into **Docker secrets** (file-sourced), mounted at `/run/secrets/<name>`. Non-secret config (`RAG_ENABLED`, `SQUARE_ENVIRONMENT`, feature flags) stays in `.env`/compose env.
- The **backend reads secrets from files** via Spring Boot `spring.config.import=optional:configtree:/run/secrets/`; **Postgres** uses the `*_FILE` convention (`POSTGRES_PASSWORD_FILE`).
- `docker-compose.yml` gains a top-level **`secrets:`** block (file sources) and **`image:` tags** on the built services, so the **same file works for both** local `docker compose up` (file-based secret bind-mounts) and prod `docker stack deploy` (real swarm secrets).
- The **GitHub Actions deploy job** switches from `docker compose up -d --build` to `docker compose build` + `docker stack deploy` — pushing to master still "just deploys."
- **`--autolock` is intentionally NOT enabled** (it would require a passphrase on every daemon restart). Encryption-at-rest is therefore raft-key-on-disk strength — still strictly better than a plaintext `.env`.
- A short **`DEPLOY.md`** documents the one-time VPS setup (swarm init + creating the secret files) and the rotation procedure.

## Capabilities

### New Capabilities
- `deployment-secrets`: how production secrets are provided to the running services (file-based Docker secrets, off environment variables) and the deployment model that delivers them.

### Modified Capabilities
*(none — no application feature or API changes.)*

## Impact

- **Infra**: `docker-compose.yml` (`secrets:`, `image:` tags, Postgres `*_FILE`, `deploy:` blocks for swarm), `.github/workflows/deploy.yml` (build + `stack deploy`), new `DEPLOY.md`.
- **Backend**: `application.yml` adds the `configtree` import; secret-bearing properties keep their names but resolve from `/run/secrets/<property-name>` files. No Java code change expected (configtree maps file → property).
- **VPS (one-time, operator)**: `docker swarm init`; split the secret values out of `.env` into `chmod 600` files; first `stack deploy`.
- **Local dev**: unchanged — the same compose file runs under `docker compose up` with file-based secrets.
- **No DB change, no app feature change.** Square/RAG/etc. behave identically; only how the keys reach the process changes.
- **Out of scope / Non-goals**: a managed secrets manager (Vault/Doppler/Infisical); `--autolock`; multi-node swarm or an image registry; rotating/replacing the actual secret values (operational, separate). The highest-leverage *public-repo* controls — branch protection on `master` and a least-privilege Square token — are complementary and tracked separately, not in this change.

## Verification

- Backend boots reading every secret from `/run/secrets/*` (no secret env vars set); `docker inspect` on the running service shows **no** secret values in `Env`.
- `docker compose up` locally still works with the same file (file-based secrets bind-mounted).
- A push to `master` deploys via `docker stack deploy` with no manual steps; the backend health check goes healthy.
- Square (prod), RAG answers, and KB/SOP sync work unchanged after cutover.
