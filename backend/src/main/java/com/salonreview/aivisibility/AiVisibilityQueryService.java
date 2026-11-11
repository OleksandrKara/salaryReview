package com.salonreview.aivisibility;

import com.salonreview.domain.AiVisibilityCheck;
import com.salonreview.domain.AiVisibilityCheck.Engine;
import com.salonreview.domain.AiVisibilityQuery;
import com.salonreview.domain.AiVisibilityRun;
import com.salonreview.domain.AiVisibilitySettings;
import com.salonreview.repo.AiVisibilityCheckRepository;
import com.salonreview.repo.AiVisibilityQueryRepository;
import com.salonreview.repo.AiVisibilityRunRepository;
import com.salonreview.repo.AiVisibilitySettingsRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Read model for the AI tab, the digest and the internal summary (design.md D5). Everything is
 * computed from {@code ai_visibility_check} rows on read; at ~10k rows a year there is no cache. */
@Service
public class AiVisibilityQueryService {

    static final int MIN_CHECKS_FOR_PERCENT = 3;
    static final Duration RECENT_WINDOW = Duration.ofDays(56);
    static final Duration INSTEAD_WINDOW = Duration.ofDays(28);
    static final Set<AiVisibilityQuery.Status> VISIBLE = Set.of(AiVisibilityQuery.Status.PROPOSED, AiVisibilityQuery.Status.ACTIVE, AiVisibilityQuery.Status.PAUSED);

    private final AiVisibilitySettingsRepository settingsRepo;
    private final AiVisibilityQueryRepository queries;
    private final AiVisibilityRunRepository runs;
    private final AiVisibilityCheckRepository checks;
    private final AiVisibilityRunService runService;

    public AiVisibilityQueryService(AiVisibilitySettingsRepository settingsRepo, AiVisibilityQueryRepository queries,
            AiVisibilityRunRepository runs, AiVisibilityCheckRepository checks, AiVisibilityRunService runService) {
        this.settingsRepo = settingsRepo;
        this.queries = queries;
        this.runs = runs;
        this.checks = checks;
        this.runService = runService;
    }

    public record Consistency(int named, int total, Integer percent) {
        static Consistency of(int named, int total) {
            return new Consistency(named, total, total >= MIN_CHECKS_FOR_PERCENT ? Math.round(100f * named / total) : null);
        }
    }

    /** pointsNamed/pointsTotal: for a question checked from several search points, how many of the
     * latest run's points named us (null for a single check). latest = NAMED if any point did. */
    public record Cell(String latest, Instant checkedAt, Short rank, Consistency recent, Consistency allTime, Integer pointsNamed,
            Integer pointsTotal, boolean asked) {
    }

    public record Row(Long id, String query, String topic, String targetUrl, String status, Map<Engine, Cell> cells,
            List<String> engines, boolean allPoints) {
    }

    public record EngineTotal(Engine engine, int named, int answered, Integer previousNamed) {
    }

    public record Ranked(String name, int count, List<String> queries) {
    }

    public record Overview(boolean configured, Instant lastRunAt, Instant nextRunAt, BigDecimal monthSpend, BigDecimal monthlyBudget,
            boolean digestEnabled, String nextActions, List<EngineTotal> engineTotals, List<Row> rows,
            List<Ranked> placesInstead, List<Ranked> sourcesInstead, Long runningRunId, List<Map<String, Object>> searchPoints,
            Integer runningDone, Integer runningTotal) {
    }

    public record HistoryItem(Long id, Engine engine, String model, String searchPoint, Instant checkedAt, String status, Boolean mentioned, Short rank,
            Boolean ownSiteCited, List<String> placesNamed, List<Map<String, String>> sourcesCited, List<String> subQueries,
            String excerpt, String error) {
    }

    public Overview overview(Long businessId) {
        return overview(businessId, null);
    }

    /** {@code site} as in {@link com.salonreview.seo.SeoSite}: "academy" keeps the PMU Academy's
     * questions (topic "academy" or an academy target page), "studio" the rest, null/"all" every
     * question. Totals, "named instead" and "sources instead" are computed over the same subset. */
    public Overview overview(Long businessId, String site) {
        Optional<AiVisibilitySettings> settings = settingsRepo.findById(businessId);
        List<AiVisibilityQuery> qs = queries.findByBusinessIdAndStatusInOrderBySortOrderAscIdAsc(businessId, VISIBLE).stream()
                .filter(q -> inSite(site, q)).toList();
        java.util.Set<Long> qIds = qs.stream().map(AiVisibilityQuery::getId).collect(java.util.stream.Collectors.toSet());
        boolean scoped = !com.salonreview.seo.SeoSite.isAll(site);
        List<AiVisibilityCheck> all = checks.findByBusinessIdOrderByCheckedAtDesc(businessId).stream()
                .filter(c -> !scoped || qIds.contains(c.getQueryId())).toList();
        Instant now = Instant.now();

        Map<Long, Map<Engine, List<AiVisibilityCheck>>> byQuery = new HashMap<>();
        for (AiVisibilityCheck c : all) {
            byQuery.computeIfAbsent(c.getQueryId(), k -> new EnumMap<>(Engine.class)).computeIfAbsent(c.getEngine(), k -> new ArrayList<>()).add(c);
        }
        List<Row> rows = new ArrayList<>();
        for (AiVisibilityQuery q : qs) {
            Map<Engine, Cell> cells = new EnumMap<>(Engine.class);
            Map<Engine, List<AiVisibilityCheck>> perEngine = byQuery.getOrDefault(q.getId(), Map.of());
            List<Engine> asked = AiVisibilityRunService.enginesFor(q);
            for (Engine e : Engine.values()) {
                Cell c = cell(perEngine.getOrDefault(e, List.of()), now);
                cells.put(e, asked.contains(e) ? c : new Cell("NOT_ASKED", null, null, c.recent(), c.allTime(), null, null, false));
            }
            rows.add(new Row(q.getId(), q.getQueryText(), q.getTopic(), q.getTargetUrl(), q.getStatus().name(), cells, q.getEngines(), q.isAllPoints()));
        }

        List<AiVisibilityRun> lastTwo = runs.findTop2ByBusinessIdAndTriggerAndFinishedAtIsNotNullOrderByStartedAtDesc(businessId, AiVisibilityRun.Trigger.SCHEDULED);
        List<EngineTotal> totals = engineTotals(businessId, lastTwo, scoped ? qIds : null);
        AiVisibilityRun runningRun = runs.findTop1ByBusinessIdOrderByStartedAtDesc(businessId)
                .filter(r -> r.getFinishedAt() == null && r.getStartedAt().isAfter(now.minus(Duration.ofHours(24)))).orElse(null);
        // An unfinished run is shown as running even while a restart is being picked up (resumeStaleRuns).
        Long running = runningRun == null ? null : runningRun.getId();
        Integer runningDone = null, runningTotal = null;
        if (runningRun != null) {
            runningDone = (int) checks.findByRunId(runningRun.getId()).stream().map(AiVisibilityCheck::getQueryId).distinct().count();
            runningTotal = runningRun.getQueryIds() != null ? runningRun.getQueryIds().size()
                    : (int) qs.stream().filter(q -> q.getStatus() == AiVisibilityQuery.Status.ACTIVE).count();
        }

        List<AiVisibilityCheck> recentMissing = all.stream()
                .filter(c -> c.getStatus() == AiVisibilityCheck.Status.OK && Boolean.FALSE.equals(c.getMentioned())
                        && c.getCheckedAt().isAfter(now.minus(INSTEAD_WINDOW)))
                .toList();
        Map<Long, String> queryText = new HashMap<>();
        for (AiVisibilityQuery q : qs) queryText.put(q.getId(), q.getQueryText());
        List<String> ownDomains = settings.map(AiVisibilitySettings::getOwnDomains).orElse(List.of());

        return new Overview(settings.isPresent(),
                lastTwo.isEmpty() ? null : lastTwo.get(0).getStartedAt(), nextScheduledRun(now),
                runService.monthSpend(businessId), settings.map(AiVisibilitySettings::getMonthlyBudgetUsd).orElse(null),
                settings.map(AiVisibilitySettings::isDigestEnabled).orElse(false), settings.map(AiVisibilitySettings::getNextActions).orElse(null),
                totals, rows, rankPlaces(recentMissing, queryText, 8), rankSources(recentMissing, queryText, ownDomains, 8), running,
                settings.map(AiVisibilitySettings::getSearchPoints).orElse(List.of()), runningDone, runningTotal);
    }

    static boolean inSite(String site, AiVisibilityQuery q) {
        if (com.salonreview.seo.SeoSite.isAll(site)) return true;
        boolean academy = "academy".equalsIgnoreCase(q.getTopic()) || com.salonreview.seo.SeoSite.isAcademyUrl(q.getTargetUrl());
        return com.salonreview.seo.SeoSite.ACADEMY.equals(site) ? academy : !academy;
    }

    static Cell cell(List<AiVisibilityCheck> newestFirst, Instant now) {
        if (newestFirst.isEmpty()) return new Cell("NONE", null, null, Consistency.of(0, 0), Consistency.of(0, 0), null, null, true);
        AiVisibilityCheck latest = newestFirst.get(0);
        // A multi-point question has several checks in its latest run: summarize them together.
        List<AiVisibilityCheck> latestRun = newestFirst.stream().filter(c -> c.getRunId() != null && c.getRunId().equals(latest.getRunId())).toList();
        Integer pointsNamed = null, pointsTotal = null;
        String state;
        Short rank = latest.getMentionRank();
        if (latestRun.size() > 1) {
            List<AiVisibilityCheck> ok = latestRun.stream().filter(c -> c.getStatus() == AiVisibilityCheck.Status.OK).toList();
            pointsTotal = latestRun.size();
            pointsNamed = (int) ok.stream().filter(c -> Boolean.TRUE.equals(c.getMentioned())).count();
            state = pointsNamed > 0 ? "NAMED" : !ok.isEmpty() ? "NOT_NAMED"
                    : latestRun.stream().allMatch(c -> c.getStatus() == AiVisibilityCheck.Status.ERROR) ? "ERROR" : "NO_ANSWER";
            rank = ok.stream().map(AiVisibilityCheck::getMentionRank).filter(java.util.Objects::nonNull).min(Short::compare).orElse(null);
        } else {
            state = switch (latest.getStatus()) {
                case OK -> Boolean.TRUE.equals(latest.getMentioned()) ? "NAMED" : "NOT_NAMED";
                case NO_ANSWER -> "NO_ANSWER";
                case ERROR -> "ERROR";
            };
        }
        int named = 0, total = 0, rNamed = 0, rTotal = 0;
        Instant recentFrom = now.minus(RECENT_WINDOW);
        for (AiVisibilityCheck c : newestFirst) {
            if (c.getStatus() != AiVisibilityCheck.Status.OK) continue;
            boolean n = Boolean.TRUE.equals(c.getMentioned());
            total++;
            if (n) named++;
            if (c.getCheckedAt().isAfter(recentFrom)) {
                rTotal++;
                if (n) rNamed++;
            }
        }
        return new Cell(state, latest.getCheckedAt(), rank, Consistency.of(rNamed, rTotal), Consistency.of(named, total), pointsNamed, pointsTotal, true);
    }

    List<EngineTotal> engineTotals(Long businessId, List<AiVisibilityRun> lastTwo) {
        return engineTotals(businessId, lastTwo, null);
    }

    /** {@code onlyQueries}: count just these questions' checks (a site's subset); null = all. */
    List<EngineTotal> engineTotals(Long businessId, List<AiVisibilityRun> lastTwo, java.util.Set<Long> onlyQueries) {
        List<EngineTotal> out = new ArrayList<>();
        if (lastTwo.isEmpty()) return out;
        java.util.function.Function<Long, List<AiVisibilityCheck>> runChecks = runId -> checks.findByBusinessIdAndRunId(businessId, runId)
                .stream().filter(c -> onlyQueries == null || onlyQueries.contains(c.getQueryId())).toList();
        Map<Engine, int[]> cur = countByEngine(runChecks.apply(lastTwo.get(0).getId()));
        Map<Engine, int[]> prev = lastTwo.size() > 1 ? countByEngine(runChecks.apply(lastTwo.get(1).getId())) : null;
        for (Engine e : Engine.values()) {
            int[] c = cur.getOrDefault(e, new int[2]);
            Integer p = prev == null || !prev.containsKey(e) ? null : prev.get(e)[0];
            out.add(new EngineTotal(e, c[0], c[1], p));
        }
        return out;
    }

    static Map<Engine, int[]> countByEngine(List<AiVisibilityCheck> runChecks) {
        Map<Engine, int[]> m = new EnumMap<>(Engine.class);
        for (AiVisibilityCheck c : runChecks) {
            if (c.getStatus() != AiVisibilityCheck.Status.OK) continue;
            int[] v = m.computeIfAbsent(c.getEngine(), k -> new int[2]);
            v[1]++;
            if (Boolean.TRUE.equals(c.getMentioned())) v[0]++;
        }
        return m;
    }

    static List<Ranked> rankPlaces(List<AiVisibilityCheck> missing, Map<Long, String> queryText, int limit) {
        Map<String, String> display = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        Map<String, java.util.LinkedHashSet<String>> qs = new HashMap<>();
        for (AiVisibilityCheck c : missing) {
            for (String p : c.getPlacesNamed()) {
                String key = p.toLowerCase(java.util.Locale.ROOT);
                display.putIfAbsent(key, p);
                counts.merge(key, 1, Integer::sum);
                if (queryText.containsKey(c.getQueryId())) qs.computeIfAbsent(key, k -> new java.util.LinkedHashSet<>()).add(queryText.get(c.getQueryId()));
            }
        }
        return top(counts, display, qs, limit);
    }

    static List<Ranked> rankSources(List<AiVisibilityCheck> missing, Map<Long, String> queryText, List<String> ownDomains, int limit) {
        Map<String, Integer> counts = new HashMap<>();
        Map<String, java.util.LinkedHashSet<String>> qs = new HashMap<>();
        for (AiVisibilityCheck c : missing) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (Map<String, String> s : c.getSourcesCited()) {
                String d = s.get("domain");
                if (d == null || MentionDetector.isOwnDomain(d, ownDomains) || !seen.add(d)) continue;
                counts.merge(d, 1, Integer::sum);
                if (queryText.containsKey(c.getQueryId())) qs.computeIfAbsent(d, k -> new java.util.LinkedHashSet<>()).add(queryText.get(c.getQueryId()));
            }
        }
        Map<String, String> display = new HashMap<>();
        counts.keySet().forEach(k -> display.put(k, k));
        return top(counts, display, qs, limit);
    }

    private static List<Ranked> top(Map<String, Integer> counts, Map<String, String> display, Map<String, java.util.LinkedHashSet<String>> qs, int limit) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .limit(limit)
                .map(e -> new Ranked(display.get(e.getKey()), e.getValue(), new ArrayList<>(qs.getOrDefault(e.getKey(), new java.util.LinkedHashSet<>()))))
                .toList();
    }

    public List<HistoryItem> history(Long businessId, Long queryId, int limit) {
        return checks.findByBusinessIdAndQueryIdOrderByCheckedAtDesc(businessId, queryId).stream().limit(limit)
                .map(c -> new HistoryItem(c.getId(), c.getEngine(), c.getModel(), c.getSearchPoint(), c.getCheckedAt(), c.getStatus().name(), c.getMentioned(),
                        c.getMentionRank(), c.getOwnSiteCited(), c.getPlacesNamed(), c.getSourcesCited(), c.getSubQueries(),
                        c.getAnswerExcerpt(), c.getError()))
                .toList();
    }

    /** Next Monday 06:00 PT (the scheduler's cron), for the "next check" label. */
    static Instant nextScheduledRun(Instant now) {
        ZonedDateTime pt = now.atZone(AiVisibilityRunService.PT);
        ZonedDateTime candidate = pt.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY)).atTime(LocalTime.of(6, 0)).atZone(AiVisibilityRunService.PT);
        if (!candidate.isAfter(pt)) candidate = candidate.plusWeeks(1);
        return candidate.toInstant();
    }

    /** Questions currently ACTIVE, for the internal summary. */
    public List<AiVisibilityQuery> activeQueries(Long businessId) {
        List<AiVisibilityQuery> list = new ArrayList<>(queries.findByBusinessIdAndStatusInOrderBySortOrderAscIdAsc(businessId, List.of(AiVisibilityQuery.Status.ACTIVE)));
        list.sort(Comparator.comparingInt(AiVisibilityQuery::getSortOrder));
        return list;
    }

    public record WeekPoint(java.time.LocalDate weekStart, Map<Engine, Integer> enginePercent, Integer usPercent,
            Map<String, Integer> placePercent, int answers) {
    }

    public record Trends(List<WeekPoint> weeks, List<String> topPlaces) {
    }

    /** Weekly visibility per engine and share of answers naming us vs the most-named competitors
     * (design follow-up 2026-10-13: VYDAI shows these as charts). Weeks start Monday, PT. A week
     * with no OK answers for an engine has a null point for it. */
    public Trends trends(Long businessId, int weeks) {
        Instant from = Instant.now().minus(Duration.ofDays(7L * weeks));
        List<AiVisibilityCheck> ok = checks.findByBusinessIdAndCheckedAtAfterOrderByCheckedAtDesc(businessId, from).stream()
                .filter(c -> c.getStatus() == AiVisibilityCheck.Status.OK).toList();
        Map<String, Integer> placeCounts = new HashMap<>();
        Map<String, String> display = new HashMap<>();
        for (AiVisibilityCheck c : ok) {
            for (String p : new java.util.LinkedHashSet<>(c.getPlacesNamed())) {
                if (Boolean.TRUE.equals(c.getMentioned()) && placeIsUs(p, businessId)) continue;
                String key = placeKey(p);
                display.putIfAbsent(key, p);
                placeCounts.merge(key, 1, Integer::sum);
            }
        }
        List<String> topKeys = placeCounts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5).map(Map.Entry::getKey).toList();
        java.util.TreeMap<java.time.LocalDate, List<AiVisibilityCheck>> byWeek = new java.util.TreeMap<>();
        for (AiVisibilityCheck c : ok) {
            java.time.LocalDate wk = c.getCheckedAt().atZone(AiVisibilityRunService.PT).toLocalDate()
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            byWeek.computeIfAbsent(wk, x -> new ArrayList<>()).add(c);
        }
        List<WeekPoint> points = new ArrayList<>();
        byWeek.forEach((wk, list) -> {
            Map<Engine, Integer> eng = new EnumMap<>(Engine.class);
            for (Engine e : Engine.values()) {
                List<AiVisibilityCheck> le = list.stream().filter(c -> c.getEngine() == e).toList();
                if (!le.isEmpty()) eng.put(e, Math.round(100f * le.stream().filter(c -> Boolean.TRUE.equals(c.getMentioned())).count() / le.size()));
            }
            int us = Math.round(100f * list.stream().filter(c -> Boolean.TRUE.equals(c.getMentioned())).count() / list.size());
            Map<String, Integer> places = new LinkedHashMap<>();
            for (String key : topKeys) {
                long n = list.stream().filter(c -> c.getPlacesNamed().stream().anyMatch(p -> placeKey(p).equals(key))).count();
                places.put(display.get(key), Math.round(100f * n / list.size()));
            }
            points.add(new WeekPoint(wk, eng, us, places, list.size()));
        });
        return new Trends(points, topKeys.stream().map(display::get).toList());
    }

    public record ProgressWeek(LocalDate weekStart, Integer mentionedPercent, Integer citedPercent, Double avgRank, int answers,
            int questions, boolean partial) {
    }

    /** {@code basis}: "pairs" = same (question, assistant) pairs in both weeks; "rates" = no shared pairs, plain weekly rates. */
    public record ProgressDelta(String direction, Integer mentionedPoints, Integer citedPoints, int comparedPairs,
            LocalDate from, LocalDate to, String basis) {
    }

    /** {@code mentionedPercent}: per week, share of this question's OK answers that name us (null = no answers). */
    public record QueryProgress(Long queryId, String text, List<Integer> mentionedPercent) {
    }

    public record Progress(List<ProgressWeek> weeks, ProgressDelta delta, List<QueryProgress> queries, int weeksAvailable) {
    }

    static final int FLAT_THRESHOLD_POINTS = 2;

    /** Weekly progress for one site and optionally one engine. Headline metric: share of OK answers that
     * name us, plus share that cite our own site and the average position when named. The week-over-week
     * delta compares the same (question, engine) pairs in the last two weeks, because the number of
     * checks differs between weeks. Weeks start Monday, PT. */
    public Progress progress(Long businessId, String site, Engine engine, int weeks) {
        List<AiVisibilityQuery> qs = queries.findByBusinessIdAndStatusInOrderBySortOrderAscIdAsc(businessId, VISIBLE).stream()
                .filter(q -> inSite(site, q)).toList();
        Map<Long, String> text = new LinkedHashMap<>();
        for (AiVisibilityQuery q : qs) text.put(q.getId(), q.getQueryText());
        Instant from = Instant.now().minus(Duration.ofDays(7L * weeks));
        List<AiVisibilityCheck> ok = checks.findByBusinessIdAndCheckedAtAfterOrderByCheckedAtDesc(businessId, from).stream()
                .filter(c -> c.getStatus() == AiVisibilityCheck.Status.OK && text.containsKey(c.getQueryId())
                        && (engine == null || c.getEngine() == engine)).toList();
        return buildProgress(text, ok);
    }

    static Progress buildProgress(Map<Long, String> text, List<AiVisibilityCheck> ok) {
        java.util.TreeMap<LocalDate, List<AiVisibilityCheck>> byWeek = new java.util.TreeMap<>();
        for (AiVisibilityCheck c : ok) byWeek.computeIfAbsent(weekStart(c), x -> new ArrayList<>()).add(c);
        List<LocalDate> weekKeys = new ArrayList<>(byWeek.keySet());

        List<ProgressWeek> points = new ArrayList<>();
        byWeek.forEach((wk, list) -> {
            long named = list.stream().filter(c -> Boolean.TRUE.equals(c.getMentioned())).count();
            long cited = list.stream().filter(c -> Boolean.TRUE.equals(c.getOwnSiteCited())).count();
            Double rank = list.stream().filter(c -> Boolean.TRUE.equals(c.getMentioned()) && c.getMentionRank() != null)
                    .mapToInt(c -> c.getMentionRank()).average().stream().boxed().findFirst()
                    .map(r -> Math.round(r * 10) / 10.0).orElse(null);
            int nq = (int) list.stream().map(AiVisibilityCheck::getQueryId).distinct().count();
            boolean partial = wk.equals(LocalDate.now(AiVisibilityRunService.PT).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
            points.add(new ProgressWeek(wk, pct(named, list.size()), pct(cited, list.size()), rank, list.size(), nq, partial));
        });

        ProgressDelta delta = null;
        if (weekKeys.size() >= 2) {
            LocalDate prev = weekKeys.get(weekKeys.size() - 2), cur = weekKeys.get(weekKeys.size() - 1);
            Map<String, double[]> a = pairShares(byWeek.get(prev)), b = pairShares(byWeek.get(cur));
            double sn1 = 0, sc1 = 0, sn2 = 0, sc2 = 0;
            int n = 0;
            for (var e : a.entrySet()) {
                double[] y = b.get(e.getKey());
                if (y == null) continue;
                n++;
                sn1 += e.getValue()[0]; sc1 += e.getValue()[1]; sn2 += y[0]; sc2 += y[1];
            }
            if (n > 0) {
                int dn = (int) Math.round(100 * (sn2 - sn1) / n), dc = (int) Math.round(100 * (sc2 - sc1) / n);
                String dir = Math.abs(dn) < FLAT_THRESHOLD_POINTS ? "flat" : dn > 0 ? "up" : "down";
                delta = new ProgressDelta(dir, dn, dc, n, prev, cur, "pairs");
            } else {
                ProgressWeek p0 = points.get(points.size() - 2), p1 = points.get(points.size() - 1);
                if (p0.mentionedPercent() != null && p1.mentionedPercent() != null) {
                    int dn = p1.mentionedPercent() - p0.mentionedPercent();
                    int dc = (p1.citedPercent() == null ? 0 : p1.citedPercent()) - (p0.citedPercent() == null ? 0 : p0.citedPercent());
                    String dir = Math.abs(dn) < FLAT_THRESHOLD_POINTS ? "flat" : dn > 0 ? "up" : "down";
                    delta = new ProgressDelta(dir, dn, dc, 0, prev, cur, "rates");
                }
            }
        }

        List<QueryProgress> rows = new ArrayList<>();
        for (var e : text.entrySet()) {
            List<Integer> series = new ArrayList<>();
            boolean any = false;
            for (LocalDate wk : weekKeys) {
                List<AiVisibilityCheck> l = byWeek.get(wk).stream().filter(c -> c.getQueryId().equals(e.getKey())).toList();
                Integer p = l.isEmpty() ? null : pct(l.stream().filter(c -> Boolean.TRUE.equals(c.getMentioned())).count(), l.size());
                any |= p != null;
                series.add(p);
            }
            if (any) rows.add(new QueryProgress(e.getKey(), e.getValue(), series));
        }
        return new Progress(points, delta, rows, points.size());
    }

    private static LocalDate weekStart(AiVisibilityCheck c) {
        return c.getCheckedAt().atZone(AiVisibilityRunService.PT).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private static Integer pct(long n, int total) {
        return total == 0 ? null : (int) Math.round(100.0 * n / total);
    }

    /** (query, engine) -> {mentioned share, cited share} over that pair's checks in the week. */
    private static Map<String, double[]> pairShares(List<AiVisibilityCheck> list) {
        Map<String, List<AiVisibilityCheck>> g = new HashMap<>();
        for (AiVisibilityCheck c : list) g.computeIfAbsent(c.getQueryId() + ":" + c.getEngine(), k -> new ArrayList<>()).add(c);
        Map<String, double[]> out = new HashMap<>();
        g.forEach((k, l) -> out.put(k, new double[] {
                l.stream().filter(c -> Boolean.TRUE.equals(c.getMentioned())).count() / (double) l.size(),
                l.stream().filter(c -> Boolean.TRUE.equals(c.getOwnSiteCited())).count() / (double) l.size()}));
        return out;
    }

    static String placeKey(String p) {
        return p.replace("&amp;", "&").toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9&]+", " ").trim();
    }

    private boolean placeIsUs(String place, Long businessId) {
        return settingsRepo.findById(businessId).map(s -> MentionDetector.containsAlias(place, s.getBrandAliases())).orElse(false);
    }
}
