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
import com.salonreview.repo.BusinessRepository;
import com.salonreview.telegram.TelegramNotificationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Weekly Telegram digest per business (design.md D8): what changed, who AI names instead, which
 * sources it reads where we are missing, Claude's current actions, spend. Russian, no em-dashes. */
@Service
public class AiVisibilityDigestService {

    static final Map<Engine, String> LABEL = new EnumMap<>(Map.of(
            Engine.CHATGPT, "ChatGPT", Engine.GOOGLE_AI_MODE, "Google AI Mode", Engine.GEMINI, "Gemini",
            Engine.PERPLEXITY, "Perplexity", Engine.CLAUDE, "Claude", Engine.GOOGLE_AI_OVERVIEW, "AI Overview"));
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM");

    private final AiVisibilitySettingsRepository settingsRepo;
    private final AiVisibilityQueryRepository queries;
    private final AiVisibilityRunRepository runs;
    private final AiVisibilityCheckRepository checks;
    private final AiVisibilityRunService runService;
    private final BusinessRepository businesses;
    private final TelegramNotificationService telegram;
    private final AiVisibilityPageService pageService;
    private final String publicBaseUrl;

    public AiVisibilityDigestService(AiVisibilitySettingsRepository settingsRepo, AiVisibilityQueryRepository queries,
            AiVisibilityRunRepository runs, AiVisibilityCheckRepository checks, AiVisibilityRunService runService,
            BusinessRepository businesses, TelegramNotificationService telegram, AiVisibilityPageService pageService,
            @Value("${app.public-base-url}") String publicBaseUrl) {
        this.settingsRepo = settingsRepo;
        this.queries = queries;
        this.runs = runs;
        this.checks = checks;
        this.runService = runService;
        this.businesses = businesses;
        this.telegram = telegram;
        this.pageService = pageService;
        this.publicBaseUrl = publicBaseUrl;
    }

    /** Sends the digest for a just-finished scheduled run; returns false when nothing was sent. */
    public boolean sendFor(AiVisibilityRun run) {
        return sendFor(run, false);
    }

    public boolean sendFor(AiVisibilityRun run, boolean includePageGaps) {
        AiVisibilitySettings settings = settingsRepo.findById(run.getBusinessId()).orElse(null);
        if (settings == null || !settings.isDigestEnabled() || run.getChecksOk() == 0) return false;
        List<AiVisibilityRun> lastTwo = runs.findTop2ByBusinessIdAndTriggerAndFinishedAtIsNotNullOrderByStartedAtDesc(run.getBusinessId(), AiVisibilityRun.Trigger.SCHEDULED);
        AiVisibilityRun previous = lastTwo.stream().filter(r -> !r.getId().equals(run.getId())).findFirst().orElse(null);
        String text = build(settings, run, previous);
        if (includePageGaps) text = withPageGaps(text, run.getBusinessId());
        return telegram.sendOwnerAlert(run.getBusinessId(), text);
    }

    String build(AiVisibilitySettings settings, AiVisibilityRun run, AiVisibilityRun previous) {
        Long businessId = settings.getBusinessId();
        List<AiVisibilityCheck> cur = checks.findByBusinessIdAndRunId(businessId, run.getId());
        List<AiVisibilityCheck> prev = previous == null ? List.of() : checks.findByBusinessIdAndRunId(businessId, previous.getId());
        Map<Long, String> qText = new HashMap<>();
        for (AiVisibilityQuery q : queries.findAllById(cur.stream().map(AiVisibilityCheck::getQueryId).distinct().toList())) {
            if (q.getBusinessId().equals(businessId)) qText.put(q.getId(), q.getQueryText());
        }
        String name = businesses.findById(businessId).map(b -> b.getName()).orElse("");
        StringBuilder sb = new StringBuilder("🤖 AI-рекомендации, ").append(name).append(", ")
                .append(DAY.format(run.getStartedAt().atZone(AiVisibilityRunService.PT))).append('\n');

        Map<Engine, int[]> c = AiVisibilityQueryService.countByEngine(cur);
        Map<Engine, int[]> p = previous == null ? null : AiVisibilityQueryService.countByEngine(prev);
        List<String> parts = new ArrayList<>();
        for (Engine e : Engine.values()) {
            int[] v = c.get(e);
            if (v == null) continue;
            String delta = "";
            if (p != null && p.containsKey(e)) {
                int d = v[0] - p.get(e)[0];
                delta = d > 0 ? " (+" + d + ")" : d < 0 ? " (" + d + ")" : " (=)";
            }
            parts.add(LABEL.get(e) + " " + v[0] + "/" + v[1] + delta);
        }
        sb.append("Называют нас: ").append(String.join(" · ", parts)).append('\n');

        if (previous != null) {
            Map<String, Boolean> before = new HashMap<>();
            for (AiVisibilityCheck x : prev) if (x.getStatus() == AiVisibilityCheck.Status.OK) before.put(x.getQueryId() + "|" + x.getEngine(), Boolean.TRUE.equals(x.getMentioned()));
            List<String> gained = new ArrayList<>(), lost = new ArrayList<>();
            for (AiVisibilityCheck x : cur) {
                if (x.getStatus() != AiVisibilityCheck.Status.OK) continue;
                Boolean was = before.get(x.getQueryId() + "|" + x.getEngine());
                if (was == null) continue;
                boolean now = Boolean.TRUE.equals(x.getMentioned());
                String label = "«" + qText.getOrDefault(x.getQueryId(), "?") + "» в " + LABEL.get(x.getEngine());
                if (now && !was) gained.add(label);
                if (!now && was) lost.add(label);
            }
            if (!gained.isEmpty()) sb.append("Появились: ").append(joinCapped(gained, 3)).append('\n');
            if (!lost.isEmpty()) sb.append("Пропали: ").append(joinCapped(lost, 3)).append('\n');
        }

        List<AiVisibilityCheck> missing = cur.stream().filter(x -> x.getStatus() == AiVisibilityCheck.Status.OK && Boolean.FALSE.equals(x.getMentioned())).toList();
        var places = AiVisibilityQueryService.rankPlaces(missing, qText, 3);
        if (!places.isEmpty()) sb.append("Вместо нас чаще всего: ").append(String.join(", ", places.stream().map(r -> r.name() + " (" + r.count() + ")").toList())).append('\n');
        var sources = AiVisibilityQueryService.rankSources(missing, qText, settings.getOwnDomains(), 3);
        if (!sources.isEmpty()) sb.append("Источники, где нас нет: ").append(String.join(", ", sources.stream().map(r -> r.name() + " (" + r.count() + ")").toList())).append('\n');

        long okWithoutPlaces = cur.stream().filter(x -> x.getStatus() == AiVisibilityCheck.Status.OK && x.getPlacesNamed().isEmpty()).count();
        if (run.getChecksOk() > 0 && okWithoutPlaces * 2 > run.getChecksOk()) {
            sb.append("⚠️ В половине ответов не распознаны названия мест, проверьте разбор ответов.\n");
        }
        if ("BUDGET".equals(run.getStoppedReason())) sb.append("⚠️ Достигнут месячный лимит, часть проверок пропущена.\n");
        if (run.getChecksFailed() > 0) sb.append("Не удалось проверить: ").append(run.getChecksFailed()).append('\n');
        if (settings.getNextActions() != null && !settings.getNextActions().isBlank()) {
            sb.append("В работе у Claude: ").append(settings.getNextActions().trim()).append('\n');
        }
        BigDecimal spent = runService.monthSpend(businessId).setScale(2, RoundingMode.HALF_UP);
        sb.append("Расход за месяц: $").append(spent.toPlainString()).append(" из $").append(settings.getMonthlyBudgetUsd().setScale(0, RoundingMode.HALF_UP).toPlainString()).append('\n');
        sb.append(publicBaseUrl).append("/owner/marketing/ai");
        return sb.toString().replace('—', '-');
    }

    /** First Monday of the month only (design.md D10): at most 2 page-gap lines, inserted before
     * the spend line. */
    String withPageGaps(String text, Long businessId) {
        List<AiVisibilityPageService.PageGap> gaps;
        try {
            gaps = pageService.pageGaps(businessId);
        } catch (RuntimeException e) {
            return text;
        }
        if (gaps.isEmpty()) return text;
        StringBuilder lines = new StringBuilder("Чего не хватает на наших страницах:\n");
        for (AiVisibilityPageService.PageGap g : gaps.subList(0, Math.min(2, gaps.size()))) {
            lines.append("• ").append(ruLabel(g.component())).append(" на ").append(path(g.targetUrl()))
                    .append(" (есть у ").append(g.competitorsWith()).append(" из ").append(g.competitorsCompared())
                    .append(" конкурентов, запросов: ").append(g.queries().size()).append(")\n");
        }
        int at = text.indexOf("Расход за месяц:");
        return at < 0 ? text + "\n" + lines : text.substring(0, at) + lines + text.substring(at);
    }

    static String path(String url) {
        String p = url.replaceFirst("^https?://[^/]+", "");
        return p.isEmpty() ? "/" : p;
    }

    static String ruLabel(String component) {
        return switch (component) {
            case "BEFORE_AFTER" -> "фото до/после";
            case "FAQ" -> "FAQ";
            case "PRICES" -> "цены";
            case "REVIEWS_ON_PAGE" -> "отзывы на странице";
            case "RATING_STATED" -> "рейтинг";
            case "ARTIST_BIO" -> "блок о мастере и опыте";
            case "PROCESS_STEPS" -> "как проходит процедура";
            case "AFTERCARE" -> "уход после процедуры";
            case "VIDEO" -> "видео";
            case "BOOKING_CTA" -> "кнопка записи";
            case "FINANCING" -> "рассрочка";
            case "MAP_NAP" -> "карта и адрес";
            case "BUSINESS_SCHEMA" -> "разметка бизнеса";
            case "PRESS" -> "публикации в прессе";
            case "GUARANTEE" -> "гарантия/коррекция";
            case "UPDATED_DATE" -> "дата обновления";
            default -> component;
        };
    }

    private static String joinCapped(List<String> items, int max) {
        String s = String.join("; ", items.subList(0, Math.min(max, items.size())));
        return items.size() > max ? s + " и ещё " + (items.size() - max) : s;
    }
}
