## Context

The revenue-pulse panel on `/reports` (owner-only, streams via Suspense) currently shows a naive `projectedMonthGross = currentMTD + upcomingConfirmedGross`. This is a hard ceiling — every booking is assumed to happen — which systematically overshoots. The salon's actual experience: some confirmed bookings cancel, some no-show, walk-ins partially compensate. Without ground-truth bias correction, the owner can't trust the projection.

We have rich data for pattern matching: `PeriodEntry` stores per-provider per-half-month revenue back to whenever the salon started, so for every settled month we already know "MTD revenue at end of day 15" (sum of `FIRST` half entries) and "month-end actual" (sum of both halves). This is enough to ship a pattern-matching forecaster immediately.

For booking-ceiling calibration we need a different signal Square doesn't expose: "what was the upcoming-booking pipeline value on day 15 of past months?" That state has moved on (reschedules, cancellations) and can't be reconstructed accurately. The only path is to start capturing it now via a daily snapshot.

## Goals / Non-Goals

**Goals:**
- Replace the naive projection with a credible range + midpoint that uses existing historical data immediately.
- Stand up a `revenue_snapshot` time-series that, after 3-6 months, enables booking-ceiling calibration.
- Keep the math pure arithmetic — no ML, no new libraries — so it's auditable and verifiable.
- Cold-start gracefully: a brand-new salon with one month of history still gets a usable projection (just a wider range).

**Non-Goals:**
- Day-of-week patterns, holiday detection, growth-trend regression — explicitly Phase 2+ work.
- Per-provider forecasting — salon-wide month-end only.
- Backfilling `revenue_snapshot` rows for past dates (would reintroduce historical-state drift).
- Writing to Square or modifying any Square data.
- Per-customer or per-service forecasts.

## Decisions

### D1: Daily snapshots, not just on the 15th

**Decision:** A `@Scheduled` job runs daily at 01:30 salon-local and captures one `revenue_snapshot` row per day, not only on the 15th.

**Rationale:** Storage is trivial (365 rows/year × ~50 bytes = ~18 KB/yr per salon). Day-1, day-7, day-22 snapshots will eventually unlock day-of-month curves (Phase 2). Sticking to only day 15 throws away free signal.

**Alternatives considered:**
- *Only day 15*: smaller dataset, no day-of-month curve later, no real benefit.
- *Every hour*: overkill; revenue changes monotonically through the day; daily is fine.

### D2: Pattern-match formula

**Decision:** `projectedMid_pattern = currentMTD / avg(firstHalfRatio over last 6 settled months)` where `firstHalfRatio = first_half_total / (first_half + second_half)` per month. We use only months with both halves settled (skip months with `null` payouts).

This works only at-or-before day 15. After day 15 we switch to `(currentMTD - day15MTD) / (1 - firstHalfRatio)` extrapolated to month-end. For days after 15 we don't have a snapshot-based MTD on day 15 unless our new snapshot ran that month, so we fall back to `currentMTD / day-of-month-progress-estimate` derived from the same half ratios.

**Rationale:** Uses data we already have via `PeriodEntryRepository`. Pure arithmetic. Six months of history is enough for a stable average.

**Alternatives considered:**
- *Linear extrapolation from daily run rate*: ignores intra-month rhythm; less accurate.
- *Per-day curve*: requires reconstructing daily revenue from `SquareMonthAggregator` for past months on every request — expensive and slow.

### D3: Booking-ceiling calibration formula

**Decision:** Once 3+ snapshot rows have a non-null `month_end_actual`, compute `biasFactor = average(month_end_actual / naive_projection)` over the most recent 6 such rows (where `naive_projection = mtd_revenue + upcoming_gross` from the snapshot). Today's calibrated projection: `projectedMid_calibrated = (currentMTD + upcomingConfirmedGross) * biasFactor`.

**Rationale:** Salons with auto-accept tend to have all-ACCEPTED bookings, so the booking ceiling is the natural anchor. The ratio captures the systematic overshoot from cancellations + no-shows + walk-in partial compensation, all in one factor.

**Alternatives considered:**
- *Show-rate from no-show table*: only counts no-shows that paid a fee; misses silent cancellations. Less robust.
- *Linear regression `actual ~ MTD + upcoming`*: heavier math, marginal accuracy gain at the data sizes we have.

### D4: Blending pattern + calibration

**Decision:** Final `projectedMid` is a weighted blend:
- If 0 calibration data points: use pattern only, weight 1.0.
- 1-2 points: pattern weight 0.7, calibration weight 0.3 (calibration is noisy).
- 3-5 points: pattern weight 0.5, calibration weight 0.5.
- 6+ points: pattern weight 0.3, calibration weight 0.7 (calibration trusts itself).

Range: `projectedLow = min(pattern, calibration) * 0.9`, `projectedHigh = max(pattern, calibration) * 1.1`. If only one technique is available, range = `mid * 0.85` to `mid * 1.15`.

**Rationale:** Lets the system improve as data accumulates without ever being unusable. Range widens when techniques disagree (real signal that this month is unusual).

### D5: Schema choice — JPA entity, not raw `JdbcTemplate`

**Decision:** `RevenueSnapshot` is a standard `@Entity` with `@Table(name = "revenue_snapshot")` mirroring other domain entities (`PeriodEntry`, `Provider`). `RevenueSnapshotRepository extends JpaRepository<RevenueSnapshot, Long>`.

**Rationale:** Consistent with rest of codebase. No new patterns introduced.

### D6: Scheduling — Spring `@Scheduled`, not Quartz

**Decision:** `@EnableScheduling` on `SalonReviewApplication`. Two methods:
- `@Scheduled(cron = "0 30 1 * * *", zone = "<salon-tz>")` — daily snapshot at 01:30 salon-local.
- `@Scheduled(cron = "0 0 2 1 * *", zone = "<salon-tz>")` — monthly actual-fill at 02:00 on day 1.

Plus an `@PostConstruct` startup hook that backfills any missing snapshot for the prior 3 days if the latest `snapshot_date` is older than yesterday.

**Rationale:** No new dependency. Spring's scheduler is rock-solid for one-replica deployments. The 3-day backfill window is enough to survive a long-weekend outage without growing unbounded.

**Salon timezone source:** read once at app startup from `SquareClient.locationTimeZone()`, fall back to UTC. Cached for the life of the JVM (no need to re-resolve).

**Alternatives considered:**
- *Quartz*: heavier; cluster-coordination matters only when running multiple replicas (we don't).
- *Custom timer thread*: reinvents Spring's wheel.

### D7: Idempotency + outage handling

**Decision:** `revenue_snapshot.snapshot_date` has a `UNIQUE` constraint. The capture service uses `INSERT ... ON CONFLICT DO NOTHING` semantics (in JPA: check-then-skip). Startup backfill iterates back up to 3 days and captures any missing date.

**Rationale:** Re-running the daily job is safe. A short outage is auto-recovered. A long outage produces a permanent gap (acceptable — the snapshot is a leading indicator, not the source of truth; PeriodEntry remains the authoritative revenue record).

### D8: Authentication / authorization

**Decision:** Forecast output is exposed via the existing `RevenuePulseService`, which is already gated to OWNER via the existing `/api/owner/**` rule in `SecurityConfig`. No new endpoint required. The scheduled job runs as a system task — no auth context needed.

### D9: Where to compute MTD inside the snapshot job

**Decision:** The capture job calls `SquareMonthAggregator.aggregate(year, month, priceCutoff)` for the current month and reads the totals as of yesterday. This uses the same code path that already powers the live month on `/owner/overview`, so the numbers will match what the owner sees.

**Rationale:** Single source of truth for "MTD revenue". No duplicated aggregation logic.

**Note:** The aggregator fetches whole-month data; we truncate post-fetch by date. With 6 months of accumulated cache pressure this could become slow — but the job runs once at 1:30 AM with no user-facing latency.

## Risks / Trade-offs

- **Cold start (no calibration data)**: For the first 3 months the projection is pattern-only and the range will be wider. We label this explicitly in the UI (e.g., "calibrating" badge) so the owner understands.
  → Mitigation: pattern matching alone is already useful at ±10-15%, much better than the naive ceiling.

- **Salon with very short history (only 1-2 settled months)**: pattern matching has high variance.
  → Mitigation: when `< 3` settled months, fall back to naive projection but show it as a single point estimate (no range), with copy "more history needed for forecasts".

- **Daily aggregator load**: the snapshot job runs `SquareMonthAggregator.aggregate()` once per day. The Square cache (3-minute TTL on bookings, 5-min on team members) is cold at 01:30 AM.
  → Mitigation: this is fine — it's off-peak, takes 2-5s, runs once.

- **Multi-replica deploy**: Spring `@Scheduled` runs on every replica. If we ever deploy >1 backend instance, snapshots would conflict on the `UNIQUE(snapshot_date)`.
  → Mitigation: the `ON CONFLICT DO NOTHING` semantics handle it cleanly — only one row wins, the others silently no-op. Documented in code.

- **Calibration ratio drift**: if the salon's mix of clients/services changes significantly, an older calibration ratio becomes stale.
  → Mitigation: rolling window of last 6 snapshots; older data drops off naturally. If we later detect drift, we can shorten to 3.

- **Range can be misleadingly wide for an unusual month**: a single anomalous calibration row (e.g., one month after a holiday) could skew the bias factor.
  → Mitigation: use median rather than mean when 6+ data points exist. Document this as a Phase 2 refinement; Phase 1 uses mean for simplicity.

- **What happens after Phase 2 features (day-of-week, growth trends) are added**: the design should compose cleanly — pattern matching becomes "richer pattern matching", calibration stays the same shape.
  → Mitigation: `RevenueForecastService` returns a single `ForecastResult` record; internal computation can evolve without touching `RevenuePulseService`'s call site.
