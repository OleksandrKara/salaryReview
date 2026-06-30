## 1. Pin the secret model

- [ ] 1.1 Read how each sensitive value is currently bound and record the exact property/file name: Square token, Anthropic key, Voyage key, LangSmith key, Postgres password, app-owner password. Confirm which `configtree` filename feeds which Spring property (e.g. `square.access-token`).
- [ ] 1.2 List the non-secret config that stays in `.env`/compose env (`RAG_ENABLED`, `AI_TRIAGE_ENABLED`, `RAG_SUGGESTIONS_ENABLED`, `SQUARE_ENVIRONMENT`, `SQUARE_LOCATION_ID`, `LANGSMITH_PROJECT`).

## 2. Backend reads file-based secrets

- [ ] 2.1 `application.yml`: add `spring.config.import=optional:configtree:/run/secrets/` (optional so env-var binding still works locally / on rollback).
- [ ] 2.2 Verify each secret-bearing property resolves from a `/run/secrets/<name>` file; add a redacted boot log line per key ("configured: true/false") to catch a filename/property mismatch early.
- [ ] 2.3 `contextLoads`/smoke test: boot with dummy secret files in a temp `configtree` dir and assert the properties bind (no real secrets in the test).

## 3. Compose: secrets + dual-mode

- [ ] 3.1 Add a top-level `secrets:` block with `file:` sources (one per secret), and reference them on the `backend`/`postgres` services.
- [ ] 3.2 Add `image:` tags to the built services (so `stack deploy` runs locally-built images by name) while keeping `build:` for `compose up`.
- [ ] 3.3 Postgres: switch to `POSTGRES_PASSWORD_FILE: /run/secrets/postgres_password`; remove the password env.
- [ ] 3.4 Add `deploy:` blocks (replicas 1, restart policy) for swarm; confirm `depends_on`/`build` are ignored cleanly by `stack deploy` and startup relies on the existing health check / DB retry.
- [ ] 3.5 Verify `docker compose up` still works locally with the same file (file-based secrets bind-mounted).

## 4. Deploy workflow

- [ ] 4.1 `.github/workflows/deploy.yml`: replace `docker compose up -d --build` with `docker compose build` + `docker stack deploy -c docker-compose.yml salonreview` over SSH; keep the health-check wait and `docker service`/`stack ps` status output.
- [ ] 4.2 Ensure the job no longer relies on env-var secrets being present in `.env` for the moved values.

## 5. Docs (DEPLOY.md)

- [ ] 5.1 One-time VPS setup: `docker swarm init`; create `secrets/` with one `chmod 600` file per secret (values from the current `.env`); trim those keys out of `.env`.
- [ ] 5.2 Rotation runbook: change a secret file → redeploy; handle swarm-secret immutability (new version, `docker secret rm` of the stale one).
- [ ] 5.3 Rollback steps (revert workflow + restore `.env`; `docker stack rm` / `docker swarm leave --force`).

## 6. Cutover & verification

- [ ] 6.1 Rehearse locally end to end (`compose build` + `stack deploy` against a single-node swarm) with dummy secrets.
- [ ] 6.2 On the VPS: one-time setup (task 5.1), then a manual `compose build` + `stack deploy`.
- [ ] 6.3 Verify: backend healthy; `docker inspect` shows no secret values in `Env`; `docker secret ls` lists the secrets; Square (prod), RAG answers, and KB/SOP sync work.
- [ ] 6.4 Enable the automated path (merge) and confirm a normal push-to-master deploy succeeds with no manual steps.
