package com.salonreview.aivisibility;

import com.salonreview.domain.AiVisibilityQuery;
import com.salonreview.domain.AiVisibilityRun;
import com.salonreview.domain.AiVisibilitySettings;
import com.salonreview.repo.AiVisibilityCheckRepository;
import com.salonreview.repo.AiVisibilityQueryRepository;
import com.salonreview.repo.AiVisibilityRunRepository;
import com.salonreview.repo.AiVisibilitySettingsRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Owner actions on questions and settings (design.md D6). Every lookup by a caller-supplied id is
 * business-scoped, so another business's id is a 404. */
@Service
public class AiVisibilityAdminService {

    static final int MAX_TRACKED = 20;
    /** Google-only "near me" questions: 2 engines x up to 6 areas each, so a separate small cap. */
    static final int MAX_NEAR_ME = 10;
    static final Set<AiVisibilityQuery.Status> COUNTED = Set.of(AiVisibilityQuery.Status.ACTIVE, AiVisibilityQuery.Status.PROPOSED);

    private final AiVisibilityQueryRepository queries;
    private final AiVisibilitySettingsRepository settingsRepo;
    private final AiVisibilityRunRepository runs;
    private final AiVisibilityCheckRepository checks;
    private final AiVisibilityRunService runService;

    public AiVisibilityAdminService(AiVisibilityQueryRepository queries, AiVisibilitySettingsRepository settingsRepo,
            AiVisibilityRunRepository runs, AiVisibilityCheckRepository checks, AiVisibilityRunService runService) {
        this.queries = queries;
        this.settingsRepo = settingsRepo;
        this.runs = runs;
        this.checks = checks;
        this.runService = runService;
    }

    @Transactional
    public AiVisibilityQuery add(Long businessId, String text, String topic, String targetUrl) {
        return add(businessId, text, topic, targetUrl, false);
    }

    /** {@code nearMe}: asked only to Google AI Overview and AI Mode, from every search area (the
     * other assistants don't know where the person is). Outside the 20-question cap. */
    @Transactional
    public AiVisibilityQuery add(Long businessId, String text, String topic, String targetUrl, boolean nearMe) {
        String t = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        if (t.length() < 8 || t.length() > 200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Question must be 8-200 characters");
        if (!nearMe && queries.countByBusinessIdAndStatusInAndEnginesIsNull(businessId, COUNTED) >= MAX_TRACKED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "20 questions are already tracked. Pause or remove one first.");
        }
        if (nearMe && queries.countByBusinessIdAndStatusIn(businessId, COUNTED) - queries.countByBusinessIdAndStatusInAndEnginesIsNull(businessId, COUNTED) >= MAX_NEAR_ME) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, MAX_NEAR_ME + " \"near me\" questions are already tracked. Pause or remove one first.");
        }
        if (queries.existsByBusinessIdAndQueryTextIgnoreCase(businessId, t)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This question is already in the list");
        }
        int order = queries.findByBusinessIdAndStatusInOrderBySortOrderAscIdAsc(businessId, AiVisibilityQueryService.VISIBLE).stream()
                .mapToInt(AiVisibilityQuery::getSortOrder).max().orElse(0) + 1;
        return queries.save(AiVisibilityQuery.builder().businessId(businessId).queryText(t)
                .topic(nearMe && (topic == null || topic.isBlank()) ? "near me" : blankToNull(topic))
                .targetUrl(blankToNull(targetUrl)).status(AiVisibilityQuery.Status.ACTIVE).activatedAt(Instant.now()).sortOrder(order)
                .engines(nearMe ? new ArrayList<>(List.of("GOOGLE_AI_OVERVIEW", "GOOGLE_AI_MODE")) : null).allPoints(nearMe)
                .build());
    }

    @Transactional
    public AiVisibilityQuery update(Long businessId, Long id, String text, String topic, String targetUrl, String status, Integer sortOrder) {
        AiVisibilityQuery q = find(businessId, id);
        if (text != null) {
            String t = text.trim().replaceAll("\\s+", " ");
            if (t.length() < 8 || t.length() > 200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Question must be 8-200 characters");
            if (!t.equalsIgnoreCase(q.getQueryText()) && queries.existsByBusinessIdAndQueryTextIgnoreCase(businessId, t)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This question is already in the list");
            }
            q.setQueryText(t);
        }
        if (topic != null) q.setTopic(blankToNull(topic));
        if (targetUrl != null) q.setTargetUrl(blankToNull(targetUrl));
        if (sortOrder != null) q.setSortOrder(sortOrder);
        if (status != null) {
            AiVisibilityQuery.Status next;
            try {
                next = AiVisibilityQuery.Status.valueOf(status);
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status");
            }
            if (COUNTED.contains(next) && !COUNTED.contains(q.getStatus()) && q.getEngines() == null
                    && queries.countByBusinessIdAndStatusInAndEnginesIsNull(businessId, COUNTED) >= MAX_TRACKED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "20 questions are already tracked. Pause or remove one first.");
            }
            if (next == AiVisibilityQuery.Status.ACTIVE && q.getActivatedAt() == null) q.setActivatedAt(Instant.now());
            q.setStatus(next);
        }
        return queries.save(q);
    }

    @Transactional
    public int approveAll(Long businessId) {
        List<AiVisibilityQuery> proposed = queries.findByBusinessIdAndStatusInOrderBySortOrderAscIdAsc(businessId, List.of(AiVisibilityQuery.Status.PROPOSED));
        Instant now = Instant.now();
        for (AiVisibilityQuery q : proposed) {
            q.setStatus(AiVisibilityQuery.Status.ACTIVE);
            if (q.getActivatedAt() == null) q.setActivatedAt(now);
        }
        queries.saveAll(proposed);
        return proposed.size();
    }

    /** One question × every engine, once per question per PT day, never while another run of this
     * business is still going. */
    public AiVisibilityRun recheck(Long businessId, Long id) {
        AiVisibilityQuery q = find(businessId, id);
        if (q.getStatus() != AiVisibilityQuery.Status.ACTIVE) throw new ResponseStatusException(HttpStatus.CONFLICT, "Only active questions can be re-checked");
        runs.findTop1ByBusinessIdOrderByStartedAtDesc(businessId)
                .filter(runService::isActive)
                .ifPresent(r -> { throw new ResponseStatusException(HttpStatus.CONFLICT, "A check is already running"); });
        Instant todayPt = LocalDate.now(AiVisibilityRunService.PT).atStartOfDay(AiVisibilityRunService.PT).toInstant();
        List<Long> todaysManual = new ArrayList<>(runs.findByBusinessIdAndTriggerAndStartedAtAfter(businessId, AiVisibilityRun.Trigger.MANUAL_QUERY, todayPt)
                .stream().map(AiVisibilityRun::getId).toList());
        if (!todaysManual.isEmpty() && checks.existsByBusinessIdAndQueryIdAndRunIdIn(businessId, id, todaysManual)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This question was already re-checked today");
        }
        return runService.startManualRecheck(businessId, q);
    }

    public AiVisibilityRun run(Long businessId, Long runId) {
        return runs.findByIdAndBusinessId(runId, businessId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @Transactional
    public AiVisibilitySettings updateSettings(Long businessId, List<String> aliases, List<String> domains, BigDecimal budget, Boolean digestEnabled) {
        return updateSettings(businessId, aliases, domains, budget, digestEnabled, null);
    }

    static final java.util.regex.Pattern COORDINATE = java.util.regex.Pattern.compile("^-?\\d{1,2}\\.\\d+,-?\\d{1,3}\\.\\d+(,\\d{1,2})?$");

    @Transactional
    public AiVisibilitySettings updateSettings(Long businessId, List<String> aliases, List<String> domains, BigDecimal budget, Boolean digestEnabled,
            List<java.util.Map<String, Object>> searchPoints) {
        AiVisibilitySettings s = settingsRepo.findById(businessId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not configured"));
        if (aliases != null) {
            List<String> clean = aliases.stream().map(String::trim).filter(a -> a.length() >= 3).distinct().toList();
            if (clean.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one name is required");
            s.setBrandAliases(new ArrayList<>(clean));
        }
        if (domains != null) {
            s.setOwnDomains(new ArrayList<>(domains.stream().map(d -> d.trim().toLowerCase().replaceFirst("^https?://", "").replaceFirst("^www\\.", "").replaceAll("/.*$", ""))
                    .filter(d -> d.contains(".")).distinct().toList()));
        }
        if (budget != null) {
            if (budget.signum() < 0 || budget.compareTo(new BigDecimal("100")) > 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Budget must be 0-100");
            s.setMonthlyBudgetUsd(budget);
        }
        if (digestEnabled != null) s.setDigestEnabled(digestEnabled);
        if (searchPoints != null) {
            List<java.util.Map<String, Object>> clean = new ArrayList<>();
            for (java.util.Map<String, Object> p : searchPoints) {
                String label = p.get("label") == null ? "" : p.get("label").toString().trim();
                String coord = p.get("coordinate") == null ? "" : p.get("coordinate").toString().replace(" ", "");
                if (label.isEmpty() || !COORDINATE.matcher(coord).matches()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each search point needs a name and \"lat,lng\" (optionally \",zoom\")");
                }
                if (coord.split(",").length == 2) coord = coord + ",13";
                java.util.Map<String, Object> m = new java.util.LinkedHashMap<>(p);
                m.put("label", label);
                m.put("coordinate", coord);
                clean.add(m);
            }
            if (clean.size() > 6) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At most 6 search points");
            s.setSearchPoints(clean);
        }
        return settingsRepo.save(s);
    }

    @Transactional
    public void setNextActions(Long businessId, String text) {
        AiVisibilitySettings s = settingsRepo.findById(businessId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not configured"));
        String t = text == null ? null : text.trim().replace('—', '-');
        s.setNextActions(t == null || t.isEmpty() ? null : (t.length() > 600 ? t.substring(0, 600) : t));
        settingsRepo.save(s);
    }

    public AiVisibilitySettings settings(Long businessId) {
        return settingsRepo.findById(businessId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not configured"));
    }

    private AiVisibilityQuery find(Long businessId, Long id) {
        return queries.findByIdAndBusinessId(id, businessId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
