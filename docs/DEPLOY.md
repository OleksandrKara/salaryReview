# Deploying to the VPS (salon.spincareer.com)

The app runs as a Docker Compose stack. Only the frontend is exposed, bound to **127.0.0.1:3000**;
the host's nginx terminates TLS for `salon.spincareer.com` and proxies to it. Postgres, the backend
and Adminer are not publicly reachable. Deploys are automatic on merge to `master`.

## One-time setup on the VPS

1. **Clone the repo** (the deploy expects it at `~/salaryReview`, or set `VPS_APP_DIR`):
   ```bash
   git clone https://github.com/OleksandrKara/salaryReview.git ~/salaryReview
   cd ~/salaryReview
   ```

2. **Create `.env`** next to `docker-compose.yml` (gitignored; never committed). Use strong values:
   ```env
   POSTGRES_PASSWORD=<strong-random>
   APP_OWNER_USERNAME=owner
   APP_OWNER_PASSWORD=<strong-password>
   SQUARE_ENVIRONMENT=production
   SQUARE_ACCESS_TOKEN=<production access token>
   SQUARE_LOCATION_ID=LNTM92CZ1PEWR
   ```
   The first OWNER account is seeded from `APP_OWNER_*` on a fresh DB. `POSTGRES_PASSWORD` only
   applies to a brand-new postgres volume; an existing DB keeps its original password.

3. **Bring it up once:**
   ```bash
   docker compose up -d --build
   ```

4. **nginx + TLS** — add the vhost and get a cert:
   ```bash
   sudo cp deploy/nginx-salon.conf.example /etc/nginx/sites-available/salon.spincareer.com
   sudo ln -s /etc/nginx/sites-available/salon.spincareer.com /etc/nginx/sites-enabled/
   sudo certbot --nginx -d salon.spincareer.com
   sudo nginx -t && sudo systemctl reload nginx
   ```
   (DNS for `salon.spincareer.com` must already point at the VPS for certbot to succeed.)

## CI / Auto-deploy (GitHub Actions)

`.github/workflows/deploy.yml` is one pipeline:
- **`test`** — runs on every push **and PR**: backend `mvn test` against a throwaway Postgres service
  (so Flyway migrations are exercised too) + frontend `tsc --noEmit` and `next build`.
- **`deploy`** — runs only after `test` passes **on a push to `master`**: SSHes in,
  `git reset --hard origin/master`, `sudo docker compose up -d --build`, and waits for the backend
  health check. It never touches `.env` or the postgres volume, so data persists across deploys.
  (The VPS user `ubuntu` isn't in the docker group, so docker is run via passwordless `sudo`.)

**Add these repo secrets** (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `VPS_HOST` | server IP or hostname |
| `VPS_USER` | SSH user with **passwordless sudo** (e.g. `ubuntu`) |
| `VPS_SSH_KEY` | a **private** key whose public key is in that user's `~/.ssh/authorized_keys` |
| `VPS_PORT` | _(optional)_ SSH port, default 22 |
| `VPS_APP_DIR` | _(optional)_ repo path, default `~/salaryReview` |

Generate a dedicated deploy key:
```bash
ssh-keygen -t ed25519 -f deploy_key -N "" -C "gh-actions-deploy"
# add deploy_key.pub to the VPS user's ~/.ssh/authorized_keys
# paste the contents of the private file `deploy_key` into the VPS_SSH_KEY secret, then delete it locally
```

## Manual deploy / rollback

```bash
ssh you@vps 'cd ~/salaryReview && git reset --hard origin/master && docker compose up -d --build'
# rollback: git reset --hard <previous-good-sha> then the same compose command
```

## Backups

`deploy/backup-postgres.sh` runs nightly on the VPS: `pg_dump -Fc` against the running Postgres
container → rotates the local copy → uploads to Google Drive via `rclone`. Defaults keep **7
days** locally and **30 days** in Drive; override with `KEEP_LOCAL_DAYS` / `KEEP_REMOTE_DAYS`.

### One-time setup on the VPS

1. **Install rclone:**
   ```bash
   sudo -v && curl https://rclone.org/install.sh | sudo bash
   ```

2. **Configure a Google Drive remote named `gdrive`:**
   ```bash
   rclone config
   # n (new remote) → name: gdrive → storage: drive → leave client_id/secret blank
   # → scope: 1 (full access) → root_folder_id / service_account_file: blank
   # → Edit advanced config: n → Use auto config: n  (headless OAuth)
   ```
   It prints a URL — open it on your laptop, sign in, paste the verification code back into the
   prompt. Then `y` to confirm, `q` to quit. Verify with `rclone lsd gdrive:` (should list your Drive).

3. **Smoke-test the script** (writes to `~/salaryReview-backups/` and `gdrive:salaryReview-backups/`,
   creating both):
   ```bash
   cd ~/salaryReview && ./deploy/backup-postgres.sh
   rclone lsl gdrive:salaryReview-backups/
   ```

4. **Schedule it** — `crontab -e` and add:
   ```cron
   30 3 * * * /home/ubuntu/salaryReview/deploy/backup-postgres.sh >> /home/ubuntu/salaryReview-backups/backup.log 2>&1
   ```
   (03:30 UTC daily. Cron output is appended to the same backup dir; rotate the log with
   `logrotate` if you care.)

### Restore

```bash
# Stop the app so nothing is writing to the DB during restore.
cd ~/salaryReview && sudo docker compose stop backend

# Copy the dump into the postgres container and restore in place.
sudo docker compose cp ~/salaryReview-backups/salonreview-YYYYMMDDTHHMMSSZ.dump postgres:/tmp/restore.dump
sudo docker compose exec -T postgres \
  pg_restore -U salon -d salonreview --clean --if-exists /tmp/restore.dump

sudo docker compose start backend
```

For a full DR drill (VPS lost), restore into a throwaway Postgres container first to confirm
the dump is good:
```bash
rclone copy gdrive:salaryReview-backups/salonreview-YYYYMMDDTHHMMSSZ.dump ./
docker run --rm -d --name pgtest -e POSTGRES_PASSWORD=x -p 55432:5432 postgres:16
docker cp salonreview-*.dump pgtest:/tmp/restore.dump
docker exec -it pgtest createdb -U postgres salonreview
docker exec -it pgtest pg_restore -U postgres -d salonreview /tmp/restore.dump
docker stop pgtest
```

## Notes
- Builds happen on the VPS (Maven + Next). On a small box this is the slow part of a deploy and
  causes a brief recreate downtime — fine for a single salon. To avoid building on the server later,
  switch to building images in CI and pushing to a registry (e.g. GHCR), then `docker compose pull`.
- Adminer is on `127.0.0.1:8081` — reach it only via an SSH tunnel: `ssh -L 8081:localhost:8081 you@vps`.
