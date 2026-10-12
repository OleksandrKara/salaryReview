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
    private final ExecutorService manualExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ai-visibility-recheck");
        t.setDaemon(true);
        return t;
    });

    public AiVisibilityRunService(AiAnswerProvider provider, AiVisibilityProperties props, AiVisibilitySettingsRepository settingsRepo,
            AiVisibilityQueryRepository queries, AiVisibilityRunRepository runs, AiVisibilityCheckRepository checks,
            CurrentBusinessContext currentBusinessContext) {
        this.provider = provider;
        this.props = props;
        this.settingsRepo = settingsRepo;
        this.queries = queries;
        this.runs = runs;
        this.checks = checks;
        this.currentBusinessContext = currentBusinessContext;
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
        AiVisibilitySettings settings = settingsRepo.findById(businessId)
                .orElseThrow(() -> new IllegalStateException("AI visibility is not configured for this business"));
        AiVisibilityRun run = runs.save(AiVisibilityRun.builder().businessId(businessId).trigger(AiVisibilityRun.Trigger.MANUAL_QUERY).build());
        manualExecutor.submit(() -> currentBusinessContext.runAs(businessId, () -> {
            try {
                execute(settings, List.of(query), run);
            } catch (RuntimeException e) {
                log.warn("AI visibility re-check failed for business {} query {}: {}", businessId, query.getId(), e.toString());
            }
        }));
        return run;
    }

    AiVisibilityRun run(AiVisibilitySettings settings, List<AiVisibilityQuery> questions, AiVisibilityRun.Trigger trigger) {
        AiVisibilityRun run = runs.save(AiVisibilityRun.builder().businessId(settings.getBusinessId()).trigger(trigger).build());
        return execute(settings, questions, run);
    }

    private AiVisibilityRun execute(AiVisibilitySettings settings, List<AiVisibilityQuery> questions, AiVisibilityRun run) {
        Long businessId = settings.getBusinessId();
        if (!props.hasCredentials()) {
            log.info("AI visibility run skipped for business {}: DATAFORSEO_LOGIN/PASSWORD not set", businessId);
            run.setStoppedReason("NO_CREDENTIALS");
            run.setFinishedAt(Instant.now());
            return runs.save(run);
        }
        BigDecimal spent = monthSpend(businessId).subtract(run.getCostUsd());
        Map<Engine, Integer> consecutiveFailures = new EnumMap<>(Engine.class);
        outer:
        for (AiVisibilityQuery q : questions) {
            for (Engine engine : Engine.values()) {
                if (consecutiveFailures.getOrDefault(engine, 0) >= ENGINE_FAILURE_LIMIT) continue;
                BigDecimal expected = expectedCost(engine);
                if (spent.add(run.getCostUsd()).add(expected).compareTo(settings.getMonthlyBudgetUsd()) > 0) {
                    run.setStoppedReason("BUDGET");
                    break outer;
                }
                AiVisibilityCheck check = checkOne(settings, q, engine, run.getId());
                checks.save(check);
                if (check.getCostUsd() != null) run.setCostUsd(run.getCostUsd().add(check.getCostUsd()));
                if (check.getStatus() == AiVisibilityCheck.Status.ERROR) {
                    run.setChecksFailed(run.getChecksFailed() + 1);
                    consecutiveFailures.merge(engine, 1, Integer::sum);
                } else {
                    consecutiveFailures.put(engine, 0);
                    if (check.getStatus() == AiVisibilityCheck.Status.OK) run.setChecksOk(run.getChecksOk() + 1);
                }
                runs.save(run);
            }
        }
        run.setFinishedAt(Instant.now());
        return runs.save(run);
    }

    AiVisibilityCheck checkOne(AiVisibilitySettings settings, AiVisibilityQuery q, Engine engine, Long runId) {
        AiVisibilityCheck.AiVisibilityCheckBuilder b = AiVisibilityCheck.builder()
                .businessId(settings.getBusinessId()).runId(runId).queryId(q.getId()).engine(engine);
        try {
            AiAnswerProvider.RawAnswer raw = provider.ask(engine, q.getQueryText(), settings.getLocationHint());
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
        };
    }
}
