## ADDED Requirements

### Requirement: Secrets delivered as files, not environment variables

Production secrets (the Square access token, Anthropic/Voyage/LangSmith keys, and the Postgres + app-owner passwords) SHALL be provided to the running services as files under `/run/secrets/`, NOT as environment variables. No secret value SHALL appear in a container's environment.

#### Scenario: Secret values are absent from the container environment
- **WHEN** the production stack is running and an operator runs `docker inspect` on a service container
- **THEN** the `Env` list contains no secret values (only non-secret config such as feature flags and `SQUARE_ENVIRONMENT`)

#### Scenario: The backend reads each secret from its file
- **WHEN** the backend starts with secrets mounted at `/run/secrets/`
- **THEN** every secret-bearing property resolves from its file (via `configtree`), and Square (production), RAG answers, and KB/SOP sync work unchanged

#### Scenario: Postgres reads its password from a file
- **WHEN** the database service starts
- **THEN** it uses `POSTGRES_PASSWORD_FILE` and authenticates without a password env var

### Requirement: Secrets stored in the swarm, not a plaintext file

Production SHALL run as a Docker Swarm and serve the sensitive values as swarm secrets (stored in the encrypted raft log), rather than a cleartext `.env` injected as env vars. The non-secret configuration MAY remain in `.env`/compose env.

#### Scenario: Sensitive values are swarm secrets
- **WHEN** the stack is deployed
- **THEN** `docker secret ls` lists the sensitive values as secrets, and they are not present as plaintext in a deployed `.env`

### Requirement: No extra recurring deployment steps

The migration SHALL NOT introduce a recurring manual step. A normal deploy SHALL remain "push to `master`," local development SHALL keep using `docker compose up` with the same compose file, and a Docker daemon restart SHALL NOT require manual intervention (i.e. `--autolock` is not enabled).

#### Scenario: Deploy stays a single push
- **WHEN** a change is pushed to `master`
- **THEN** the CI deploy job builds images and runs `docker stack deploy` with no additional manual steps, and the backend health check reports healthy

#### Scenario: Local development is unchanged
- **WHEN** a developer runs `docker compose up` locally with the same compose file
- **THEN** the services start with file-based secrets bind-mounted, without requiring a local swarm

#### Scenario: Daemon restart needs no unlock
- **WHEN** the VPS reboots or the Docker daemon restarts
- **THEN** the stack and its secrets come back up without a manual unlock passphrase
