package com.salonreview.aivisibility;

import com.salonreview.config.AiVisibilityProperties;
import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.AiVisibilityCheck;
import com.salonreview.domain.AiVisibilityCheck.Engine;
import com.salonreview.domain.AiVisibilityQuery;
import com.salonreview.domain.AiVisibilityRun;
import com.salonreview.domain.AiVisibilitySettings;
import com.salonreview.repo.AiVisibilityCheckRepository;
import com.salonreview.repo.AiVisibilityQueryRepository;
import com.salonreview.repo.AiVisibilityRunRepository;
import com.salonreview.repo.AiVisibilitySettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Asks a business's questions to every engine and stores one check per answer (design.md D3).
 * Calls are sequential; each check is saved on its own so a crash mid-run keeps what was paid for.
 * The budget guard runs before every call. */
@Service
public class AiVisibilityRunService {

    private static final Logger log = LoggerFactory.getLogger(AiVisibilityRunService.class);
    static final ZoneId PT = ZoneId.of("America/Los_Angeles");
    static final int ENGINE_FAILURE_LIMIT = 3;

    private final AiAnswerProvider provider;
    private final AiVisibilityProperties props;
    private final AiVisibilitySettingsRepository settingsRepo;
    private final AiVisibilityQueryRepository queries;
    private final AiVisibilityRunRepository runs;
    private final AiVisibilityCheckRepository checks;
    private final CurrentBusinessContext currentBusinessContext;
    private final org.springframework.beans.factory.ObjectProvider<AiVisibilityDigestService> digestProvider;
    private final ExecutorService manualExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ai-visibility-recheck");
        t.setDaemon(true);
        return t;
    });

    public AiVisibilityRunService(AiAnswerProvider provider, AiVisibilityProperties props, AiVisibilitySettingsRepository settingsRepo,
            AiVisibilityQueryRepository queries, AiVisibilityRunRepository runs, AiVisibilityCheckRepository checks,
            CurrentBusinessContext currentBusinessContext, org.springframework.beans.factory.ObjectProvider<AiVisibilityDigestService> digestProvider) {
        this.provider = provider;
        this.props = props;
        this.settingsRepo = settingsRepo;
        this.queries = queries;
        this.runs = runs;
        this.checks = checks;
        this.currentBusinessContext = currentBusinessContext;
        this.digestProvider = digestProvider;
    }

    /** Weekly run over every ACTIVE question. Returns the finished run, or empty when the business has
     * no settings or no active questions. */
    public Optional<AiVisibilityRun> runScheduled(Long businessId) {
        Optional<AiVisibilitySettings> settings = settingsRepo.findById(businessId);
        if (settings.isEmpty()) return Optional.empty();
        List<AiVisibilityQuery> active = queries.findByBusinessIdAndStatusInOrderBySortOrderAscIdAsc(businessId, List.of(AiVisibilityQuery.Status.ACTIVE));
        if (active.isEmpty()) return Optional.empty();
        return Optional.of(run(settings.get(), active, AiVisibilityRun.Trigger.SCHEDULED));
    }

    /** One question × every engine, started in the background; the caller polls the run row. */
    public AiVisibilityRun startManualRecheck(Long businessId, AiVisibilityQuery query) {
        return startManualRecheck(businessId, List.of(query));
    }

    /** Several questions in one background run (owner-selected re-check, 2026-10-13). */
    public AiVisibilityRun startManualRecheck(Long businessId, List<AiVisibilityQuery> selected) {
        AiVisibilitySettings settings = settingsRepo.findById(businessId)
                .orElseThrow(() -> new IllegalStateException("AI visibility is not configured for this business"));
        AiVisibilityRun run = runs.save(AiVisibilityRun.builder().businessId(businessId).trigger(AiVisibilityRun.Trigger.MANUAL_QUERY)
                .queryIds(new java.util.ArrayList<>(selected.stream().map(AiVisibilityQuery::getId).toList())).claimedAt(Instant.now()).build());
        manualExecutor.submit(() -> currentBusinessContext.runAs(businessId, () -> {
            try {
                execute(settings, selected, run);
            } catch (RuntimeException e) {
                log.warn("AI visibility re-check failed for business {} ({} questions): {}", businessId, selected.size(), e.toString());
            }
        }));
        return run;
    }

    AiVisibilityRun run(AiVisibilitySettings settings, List<AiVisibilityQuery> questions, AiVisibilityRun.Trigger trigger) {
        AiVisibilityRun run = runs.save(AiVisibilityRun.builder().businessId(settings.getBusinessId()).trigger(trigger).claimedAt(Instant.now()).build());
        return execute(settings, questions, run);
    }

    AiVisibilityRun execute(AiVisibilitySettings settings, List<AiVisibilityQuery> questions, AiVisibilityRun run) {
        Long businessId = settings.getBusinessId();
        if (!props.hasCredentials()) {
            log.info("AI visibility run skipped for business {}: DATAFORSEO_LOGIN/PASSWORD not set", businessId);
            run.setStoppedReason("NO_CREDENTIALS");
            run.setFinishedAt(Instant.now());
            return runs.save(run);
        }
        BigDecimal spent = monthSpend(businessId).subtract(run.getCostUsd());
        Map<Engine, Integer> consecutiveFailures = new EnumMap<>(Engine.class);
        // A resumed run (see resumeStaleRuns) skips what it already paid for before the restart.
        java.util.Set<String> done = new java.util.HashSet<>();
        if (run.getId() != null) for (AiVisibilityCheck c : checks.findByRunId(run.getId())) done.add(c.getQueryId() + "|" + c.getEngine() + "|" + c.getSearchPoint());
        outer:
        for (AiVisibilityQuery q : questions) {
            for (Engine engine : enginesFor(q)) {
                for (Map<String, Object> point : pointsFor(settings, q, engine)) {
                    if (done.contains(q.getId() + "|" + engine + "|" + (point == null ? null : point.get("label")))) continue;
                    if (consecutiveFailures.getOrDefault(engine, 0) >= ENGINE_FAILURE_LIMIT) continue;
                    BigDecimal expected = expectedCost(engine);
                    if (spent.add(run.getCostUsd()).add(expected).compareTo(settings.getMonthlyBudgetUsd()) > 0) {
                        run.setStoppedReason("BUDGET");
                        break outer;
                    }
                    run.setClaimedAt(Instant.now()); // heartbeat before a call that can take minutes with retries
                    runs.save(run);
                    AiVisibilityCheck check = checkOne(settings, q, engine, run.getId(), point);
                    checks.save(check);
                    if (check.getCostUsd() != null) run.setCostUsd(run.getCostUsd().add(check.getCostUsd()));
                    if (check.getStatus() == AiVisibilityCheck.Status.ERROR) {
                        run.setChecksFailed(run.getChecksFailed() + 1);
                        consecutiveFailures.merge(engine, 1, Integer::sum);
                    } else {
                        consecutiveFailures.put(engine, 0);
                        if (check.getStatus() == AiVisibilityCheck.Status.OK) run.setChecksOk(run.getChecksOk() + 1);
                    }
                    run.setClaimedAt(Instant.now()); // heartbeat
                    runs.save(run);
                }
            }
        }
        run.setFinishedAt(Instant.now());
        return runs.save(run);
    }

    static boolean isGoogle(Engine e) {
        return e == Engine.GOOGLE_AI_OVERVIEW || e == Engine.GOOGLE_AI_MODE;
    }

    /** The question's own engine list (Google-only "near me" questions), else every engine. */
    static List<Engine> enginesFor(AiVisibilityQuery q) {
        if (q.getEngines() == null || q.getEngines().isEmpty()) return List.of(Engine.values());
        return java.util.Arrays.stream(Engine.values()).filter(e -> q.getEngines().contains(e.name())).toList();
    }

    /** Google engines: the first search point, or every point for an "all points" question. Other
     * engines (and a business with no points) get one call with no point. */
    static List<Map<String, Object>> pointsFor(AiVisibilitySettings s, AiVisibilityQuery q, Engine e) {
        java.util.ArrayList<Map<String, Object>> none = new java.util.ArrayList<>();
        none.add(null);
        if (!isGoogle(e) || s.getSearchPoints() == null || s.getSearchPoints().isEmpty()) return none;
        return q.isAllPoints() ? s.getSearchPoints() : List.of(s.getSearchPoints().get(0));
    }

    AiVisibilityCheck checkOne(AiVisibilitySettings settings, AiVisibilityQuery q, Engine engine, Long runId) {
        return checkOne(settings, q, engine, runId, null);
    }

    AiVisibilityCheck checkOne(AiVisibilitySettings settings, AiVisibilityQuery q, Engine engine, Long runId, Map<String, Object> point) {
        String coordinate = point != null && point.get("coordinate") != null ? point.get("coordinate").toString()
                : isGoogle(engine) ? settings.getSearchCoordinate() : null;
        AiVisibilityCheck.AiVisibilityCheckBuilder b = AiVisibilityCheck.builder()
                .businessId(settings.getBusinessId()).runId(runId).queryId(q.getId()).engine(engine)
                .searchPoint(point != null && point.get("label") != null ? point.get("label").toString() : null);
        try {
            AiAnswerProvider.RawAnswer raw = provider.ask(engine, q.getQueryText(), settings.getLocationHint(), coordinate);
            b.model(raw.model()).costUsd(raw.cost());
            ParsedAnswer parsed = AiAnswerParser.parse(engine, raw.result());
            if (parsed.isEmpty()) return b.status(AiVisibilityCheck.Status.NO_ANSWER).build();
            MentionDetector.Result m = MentionDetector.detect(parsed, settings.getBrandAliases(), settings.getOwnDomains());
            return b.status(AiVisibilityCheck.Status.OK)
                    .mentioned(m.mentioned()).mentionRank(m.rank()).ownSiteCited(m.ownSiteCited())
                    .placesNamed(parsed.places()).sourcesCited(AiAnswerParser.citationMaps(parsed.citations()))
                    .subQueries(parsed.subQueries()).answerExcerpt(m.excerpt())
                    .build();
        } catch (RuntimeException e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return b.status(AiVisibilityCheck.Status.ERROR).error(msg.length() > 500 ? msg.substring(0, 500) : msg).build();
        }
    }

    /** A run is "in progress" only while it keeps writing checks: one call takes at most ~2.5 min,
     * so 5 quiet minutes means its replica went away (2026-10-13: a re-check started during a
     * blue/green deploy was killed with its container and blocked re-checks for 3 hours). */
    static final java.time.Duration STALE_AFTER = java.time.Duration.ofMinutes(5);

    public boolean isActive(AiVisibilityRun run) {
        if (run.getFinishedAt() != null) return false;
        Instant lastCheck = checks.findTop1ByRunIdOrderByCheckedAtDesc(run.getId()).map(AiVisibilityCheck::getCheckedAt).orElse(run.getStartedAt());
        Instant lastActivity = run.getClaimedAt() != null && run.getClaimedAt().isAfter(lastCheck) ? run.getClaimedAt() : lastCheck;
        return lastActivity.isAfter(Instant.now().minus(STALE_AFTER));
    }

    static final java.time.Duration GIVE_UP_AFTER = java.time.Duration.ofHours(24);

    /** Picks up runs whose replica went away mid-run (a deploy, a crash) and finishes only what is
     * left: every few minutes on every replica; the claim makes exactly one of them take it. Runs
     * older than a day are closed as INTERRUPTED instead. */
    @org.springframework.scheduling.annotation.Scheduled(initialDelay = 60_000, fixedDelay = 120_000)
    public void resumeStaleRuns() {
        try {
            for (AiVisibilityRun r : runs.findByFinishedAtIsNullAndStartedAtBefore(Instant.now().minus(java.time.Duration.ofMinutes(1)))) {
                if (isActive(r)) continue;
                if (runs.claim(r.getId(), Instant.now().minus(STALE_AFTER)) != 1) continue;
                AiVisibilityRun run = runs.findById(r.getId()).orElse(null);
                if (run == null) continue;
                if (run.getStartedAt().isBefore(Instant.now().minus(GIVE_UP_AFTER)) || settingsRepo.findById(run.getBusinessId()).isEmpty()) {
                    run.setFinishedAt(Instant.now());
                    run.setStoppedReason("INTERRUPTED");
                    runs.save(run);
                    continue;
                }
                AiVisibilitySettings settings = settingsRepo.findById(run.getBusinessId()).get();
                List<AiVisibilityQuery> qs = run.getQueryIds() == null
                        ? queries.findByBusinessIdAndStatusInOrderBySortOrderAscIdAsc(run.getBusinessId(), List.of(AiVisibilityQuery.Status.ACTIVE))
                        : queries.findAllById(run.getQueryIds()).stream().filter(q -> q.getBusinessId().equals(run.getBusinessId())
                                && q.getStatus() == AiVisibilityQuery.Status.ACTIVE).sorted(java.util.Comparator.comparingInt(AiVisibilityQuery::getSortOrder)).toList();
                log.info("Resuming AI visibility run {} (business {}, {} questions)", run.getId(), run.getBusinessId(), qs.size());
                manualExecutor.submit(() -> currentBusinessContext.runAs(run.getBusinessId(), () -> {
                    try {
                        AiVisibilityRun finished = execute(settings, qs, run);
                        if (finished.getTrigger() == AiVisibilityRun.Trigger.SCHEDULED) {
                            AiVisibilityDigestService digest = digestProvider.getIfAvailable();
                            if (digest != null) digest.sendFor(finished, java.time.LocalDate.now(PT).getDayOfMonth() <= 7);
                        }
                    } catch (RuntimeException e) {
                        log.warn("Resumed AI visibility run {} failed: {}", run.getId(), e.toString());
                    }
                }));
            }
        } catch (RuntimeException e) {
            log.warn("Resuming AI visibility runs failed: {}", e.toString());
        }
    }

    BigDecimal monthSpend(Long businessId) {
        Instant monthStart = LocalDate.now(PT).withDayOfMonth(1).atStartOfDay(PT).toInstant();
        return runs.spentSince(businessId, monthStart);
    }

    BigDecimal expectedCost(Engine engine) {
        return switch (engine) {
            case CHATGPT -> props.getChatgptExpectedCost();
            case GOOGLE_AI_MODE -> props.getGoogleAiModeExpectedCost();
            case GEMINI -> props.getGeminiExpectedCost();
            case PERPLEXITY -> props.getPerplexityExpectedCost();
            case CLAUDE -> props.getClaudeExpectedCost();
            case GOOGLE_AI_OVERVIEW -> props.getGoogleAiOverviewExpectedCost();
        };
    }
}
