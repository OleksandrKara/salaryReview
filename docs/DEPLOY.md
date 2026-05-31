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

## Auto-deploy (GitHub Actions → SSH)

`.github/workflows/deploy.yml` runs on every push to `master` (and via the Actions tab → "Run
workflow"). It SSHes in, `git reset --hard origin/master`, rebuilds, and waits for the backend to be
healthy. It never touches `.env` or the postgres volume, so data persists across deploys.

**Add these repo secrets** (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `VPS_HOST` | server IP or hostname |
| `VPS_USER` | SSH user (must be in the `docker` group) |
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

## Notes
- Builds happen on the VPS (Maven + Next). On a small box this is the slow part of a deploy and
  causes a brief recreate downtime — fine for a single salon. To avoid building on the server later,
  switch to building images in CI and pushing to a registry (e.g. GHCR), then `docker compose pull`.
- Adminer is on `127.0.0.1:8081` — reach it only via an SSH tunnel: `ssh -L 8081:localhost:8081 you@vps`.
