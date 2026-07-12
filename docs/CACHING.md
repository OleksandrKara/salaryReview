# Square data: caching, freshness & sync

How the app keeps the Square‑sourced numbers fast **and** trustworthy. Square is read **live and
read‑only** (we never write to Square), but every settlement view would otherwise re‑pull the same data
on every render, so reads are cached briefly. This doc is the single source of truth for that behaviour —
the code comments point here.

## Why

A `/reports` or `/me` render aggregates a month of Square data (bookings, orders, catalog, team members,
customers). Cold, that's ~6–9s of paginated HTTP. Within one render the same windows are pulled several
times over (the month aggregator, the no‑show detection, and the no‑show panel all overlap), and
switching months re‑pulls everything. Caching removes the repeats; an honest timestamp + a manual Sync
keep it from ever silently going stale.

## The cache

`SquareClient` holds a small in‑memory, per‑backend‑instance cache (`cached(key, ttl, loader)` over a
`ConcurrentHashMap`). Read‑only data only; TTL chosen per how often each thing actually changes:

| Data | Method | TTL |
|------|--------|-----|
| Bookings (per window) | `bookings(start,end)` | **10 min** |
| Completed orders (per window) | `completedOrders(start,end)` | **10 min** |
| Team members | `allTeamMembers` / `activeTeamMembers` | 5 min |
| Catalog prices / names (per id set) | `catalogPrices` / `catalogNames` | 10 min |
| Location timezone | `locationTimeZone` | 1 h |
| Customer names | `customerNames` | own process‑wide map (names rarely change) |

**Why 10 min for bookings/orders?** This is a periodic‑review tool, not a live dashboard — payroll is
reviewed after shifts and at period close, not monitored second‑by‑second. 10 minutes keeps a normal
review sitting instant while keeping the current month's data reasonably fresh, and the honest badge + Sync
button mean anyone who needs newer data sees the age and can force a pull. Past/closed months never
change, so caching them is risk‑free; only the current month accrues new checkouts. It's a one‑line tune
in `SquareClient` (a shorter 3 min is the freshness‑leaning alternative; 10 min leans toward fewer Square
calls, which Sync makes safe).

**Staleness guarantee:** automatically, the money‑moving tables (bookings, orders) are at most **~10 min**
behind Square; the next load after the TTL lapses pulls fresh — and **Sync** makes it current instantly.

Also: within the aggregator and the no‑show scan, the independent **bookings + orders** fetches run
**concurrently** (`CompletableFuture`), which roughly halves cold latency.

## Outbound concurrency limit

Several call sites intentionally fan work out in parallel to stay fast: `bookingsForCustomer` splits a
wide lookback into ≤30‑day windows fetched concurrently on virtual threads, and
`MarketingAnalyticsService`/`MarketingContactsService` run their per‑contact/per‑customer loops via
`.parallelStream()`. For one contact with a long history that's easily ~20 simultaneous Square calls; for
a page load touching many contacts at once, the burst can pass 100 simultaneous requests — which is
enough to trip Square's real per‑merchant rate limit (`429`, `RATE_LIMIT_ERROR`), and did in production
(uncaught, this crashed the Analytics tab with a 500).

`SquareClient` now gates **every** outbound HTTP call — not just `bookingsForCustomer`'s — through a
single fair `Semaphore(SquareClient.MAX_CONCURRENT_SQUARE_CALLS = 6)` (see the `throttled(...)` helper).
6 keeps most of the parallel‑fetch speedup (a 20‑window fan‑out finishes in ~4 batches instead of 20
sequential round trips or being fully serialized) while staying far below the real rate limit even with
several tabs/contacts active at once. It's a plain constant for now; promote it to a `SquareProperties`
setting if it ever needs prod tuning without a redeploy.

Because a single failed window can no longer be treated as fatal to the whole account, two additional
layers of graceful degradation exist below the throttle:
- `bookingsForCustomer`'s internal window fan-out returns partial results — one window failing (e.g. it
  still hits a 429 despite the throttle) logs a warning and contributes no bookings for that window,
  rather than discarding every other window's results for that customer.
- `MarketingAnalyticsService` wraps every remaining Square call it makes directly (phone‑based customer
  lookup, customer creation dates, per‑customer booking history for the fresh/upcoming checks) in
  try/catch, degrading to "exclude this one customer" or an existing conservative fallback (e.g. treating
  an unresolvable customer as "returning," matching how a customer with genuinely no data is already
  handled) rather than failing the entire `/api/owner/marketing/analytics` response. This mirrors the
  same try/catch‑and‑degrade pattern `MarketingContactsService.fetchAppointments` already used.

### Measured effect
| Path | Before | After |
|------|--------|-------|
| `/reports` preview, repeat/return | 7.5s | **0.1s** (cache hit) |
| No‑show panel | 4.7s | **0.1s** |
| `/me` detail (after reports) | 8.6s | **0.45s** |
| Cold first load of a month | 8.9s | **6.4s** (parallel fetch) |

## Honest "synced" badge

The `SyncBadge` timestamp is **the real last Square fetch time**, not the render time. `SquareClient`
records `lastFetchAt` on every cache **miss** and exposes `lastFetchAt()`; `SettlementPreviewService`
uses it for the `syncedAt` field. So when a view is served from cache, the badge truthfully reads e.g.
"synced · 2:31 PM" (40s ago), never a misleading "just now".

## Sync now (on‑demand refresh)

For when someone suspects a gap with Square and wants fresh data immediately:

- **Button:** the `Sync` control next to the badge (`SyncButton`) → `POST /api/sync` → `router.refresh()`.
  Its spinner is driven off the `syncedAt` prop changing (it stops when the fresh data lands), with a 15s
  safety timeout.
- **Endpoint:** `SquareSyncController` (`POST /api/sync`) calls `SquareClient.invalidate()` (clears the
  whole cache). Any signed‑in user may call it — it only busts a read cache.
- After invalidation the next render pulls fresh from Square and `lastFetchAt` (the badge) updates.

## Operational notes

- The cache is **in‑memory per backend instance** — it's empty after a restart/redeploy (first load is
  cold) and is **not** shared if you scale to multiple backend instances.
- The cache is **process‑wide / shared across users**, and is **not** tied to login — signing in or out
  neither clears nor triggers a pull.

## Related: how long a login lasts

Different clock from the data cache. Two cookies bound a session and the shorter wins:

- **Server session — 12 h idle** (`server.servlet.session.timeout: 12h` in `application.yml`). Resets on
  every request, so active use keeps it alive.
- **`sid` cookie — 12 h** absolute (set in `app/api/login/route.ts`).

So a login lasts **up to 12 h**: you stay signed in as long as you use it within any 12 h window, capped
at 12 h absolute by the `sid` cookie; **idle ≥ 12 h → must sign in again**. To change this, set
`server.servlet.session.timeout` (backend) and the `sid` `maxAge` (frontend) together, or wire up the
"Remember me" checkbox (currently inert).
