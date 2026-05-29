# Production deployment — VPS runbook

How `salaryReview` is deployed at **https://salon.spincareer.com** (OVH VPS,
Ubuntu). The app itself runs in Docker; **nginx, TLS, and the firewall live on
the host and are _not_ in this repo** — this doc is the source of truth for
reproducing them on a rebuild.

> **One rule that bit us once:** nginx must reverse-proxy **everything** to the
> Next.js frontend (`localhost:3000`). Do **not** add a `location /api/` block
> that proxies straight to the backend. The browser only ever holds an httpOnly
> `sid` cookie; Next.js translates it into the backend's `JSESSIONID` and
> converts the JSON login body into the form-encoded body Spring expects. A
> direct `/api/`→backend route breaks login (you get a 401).

---

## Topology

```
Internet ──443/HTTPS──▶ nginx (host) ──▶ 127.0.0.1:3000  Next.js (frontend)
                          │                                  │ server-side fetch
                          │                                  ▼
                          └─ TLS termination            backend:8080  Spring (Docker network)
                                                              │
                                                              ▼
                                                         postgres:5432  (Docker network)
```

- All published container ports bind to **`127.0.0.1`** (see `docker-compose.yml`):
  frontend `3000`, backend `8080`, adminer `8081`. Postgres publishes no host
  port. Only nginx (and SSH tunnels) can reach them — the public internet sees
  only nginx on 80/443 and sshd on 22.
- The browser never talks to the backend directly. Next.js proxies `/api/*`
  server-side (`frontend/app/lib/proxyBackend.ts`, `app/api/login/route.ts`).

---

## Prerequisites on the host

- Docker engine + Compose v2
- nginx
- certbot (+ `python3-certbot-nginx`)
- A DNS A-record for `salon.spincareer.com` → the VPS IP

## 1. App (Docker)

```bash
git clone git@github.com:OleksandrKara/salaryReview.git
cd salaryReview
# Create .env next to docker-compose.yml — NEVER commit it:
cat > .env <<'ENV'
POSTGRES_PASSWORD=<strong-random>
SQUARE_ENVIRONMENT=production
SQUARE_ACCESS_TOKEN=<square-token>
SQUARE_LOCATION_ID=<square-location-id>
APP_OWNER_USERNAME=<owner-login>
APP_OWNER_PASSWORD=<strong-random>     # seeds the first owner when the user table is empty
ENV
sudo docker compose up -d --build
```

Ports are loopback-bound in `docker-compose.yml`; nothing here is publicly
reachable yet. The first owner account is seeded from `APP_OWNER_*` on first boot.

## 2. nginx reverse proxy + TLS

Create `/etc/nginx/sites-available/salon` (symlink into `sites-enabled/`), then
run certbot to obtain the cert and inject the 443/SSL lines:

```bash
sudo certbot --nginx -d salon.spincareer.com
```

The maintained config (`server_tokens`, security headers, login rate-limit,
single proxy to `:3000`) — Certbot manages the `ssl_*`, `listen 443`, and the
HTTP→HTTPS redirect block:

```nginx
server {
    server_name salon.spincareer.com;

    server_tokens off;

    # Security headers (on every response, incl. errors, via `always`).
    add_header Strict-Transport-Security "max-age=15768000" always;  # HSTS ~6mo, this host only
    add_header X-Frame-Options "SAMEORIGIN" always;                  # clickjacking
    add_header X-Content-Type-Options "nosniff" always;             # MIME sniffing
    add_header Referrer-Policy "strict-origin-when-cross-origin" always;

    # Rate-limit logins per IP (zone in /etc/nginx/conf.d/ratelimit.conf).
    location = /api/login {
        limit_req zone=login burst=10 nodelay;
        limit_req_status 429;

        proxy_pass http://localhost:3000;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;   # lets Next.js mark cookies Secure
    }

    location / {
        proxy_pass http://localhost:3000;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }

    # --- Certbot-managed lines below (listen 443 ssl, ssl_certificate, etc.) ---
}
# Certbot also adds a port-80 server block that 301-redirects to https.
```

Rate-limit zone — `/etc/nginx/conf.d/ratelimit.conf` (must be in the `http`
context; `conf.d/*.conf` is included there):

```nginx
limit_req_zone $binary_remote_addr zone=login:10m rate=5r/m;
```

Apply: `sudo nginx -t && sudo nginx -s reload`.

`server.forward-headers-strategy: framework` (in `backend/application.yml`) makes
Spring honor `X-Forwarded-Proto`; combined with the header nginx sets, the
Next.js login route marks the auth cookie `Secure` over HTTPS automatically.

## 3. Firewall (UFW)

```bash
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw --force enable        # add allows BEFORE enabling — avoids SSH lockout
```

Default-deny incoming, allow only 22/80/443. **Caveat:** Docker can bypass UFW
by writing its own iptables rules — this setup is safe only because every
container port is bound to `127.0.0.1`. Never revert a container port to
`0.0.0.0` without re-checking exposure (`sudo ss -tlnp`).

---

## Updating a running deployment

```bash
cd ~/salaryReview
git pull
sudo docker compose up -d --build        # rebuilds changed services
```

## Verifying

```bash
curl -sI https://salon.spincareer.com/login | grep -iE 'strict-transport|x-frame|^HTTP'
curl -s -o /dev/null -w '%{http_code}\n' http://<public-ip>:3000   # want: refused (loopback-bound)
sudo ufw status verbose
```

## Notes / known gaps

- **SSH password auth is still enabled.** Key auth works; hardening step is
  `PasswordAuthentication no` (+ `PermitRootLogin no`) in
  `/etc/ssh/sshd_config.d/` once you've confirmed key login — verify before
  reloading sshd to avoid lockout.
- **adminer** (`127.0.0.1:8081`) is loopback-only — reach it via an SSH tunnel:
  `ssh -L 8081:localhost:8081 user@host`, never the public internet.
- Sessions are in-memory (Spring) — a backend restart logs everyone out. See
  `docs/ROADMAP.md` for the Spring Session JDBC follow-up.
- `.env` holds all secrets and is gitignored — back it up out-of-band.
