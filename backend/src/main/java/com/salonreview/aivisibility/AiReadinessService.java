package com.salonreview.aivisibility;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.domain.AiVisibilityQuery;
import com.salonreview.domain.AiVisibilitySettings;
import com.salonreview.repo.AiVisibilityQueryRepository;
import com.salonreview.repo.AiVisibilitySettingsRepository;
import com.salonreview.telegram.TelegramNotificationService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Weekly AI-readiness check of our own sites (Notion #26, 2026-10-15): can AI assistants read
 * what we want them to quote?
 * <ul>
 *   <li>robots.txt: not closed to GPTBot, OAI-SearchBot, ChatGPT-User, ClaudeBot, PerplexityBot,
 *       Google-Extended, Googlebot, Bingbot;</li>
 *   <li>llms.txt: present, plain text, not tiny;</li>
 *   <li>the homepage and every tracked question's target page on our domain: answers 200, the text
 *       is in the HTML (readable without JavaScript), has an H1 and JSON-LD structured data.</li>
 * </ul>
 * Results go to {@code ai_readiness_report} (shown on the AI tab); the owner-only Telegram chat hears
 * about it only when a check newly fails. Pages this server can't fetch itself (our own nails site
 * answers this VPS with 403) are read through DataForSEO without JavaScript, which is what a
 * crawler sees too.
 */
@Service
public class AiReadinessService {

    private static final Logger log = LoggerFactory.getLogger(AiReadinessService.class);

    static final List<String> AI_BOTS = List.of("GPTBot", "OAI-SearchBot", "ChatGPT-User", "ClaudeBot", "PerplexityBot",
            "Google-Extended", "Googlebot", "Bingbot");
    static final int MIN_WORDS_NO_JS = 150;
    static final int MIN_LLMS_BYTES = 2000;
    static final int MAX_PAGES = 30;
    private static final String TEXT_JS = "document.body ? document.body.innerText.slice(0,40000) : ''";

    /** One check: {@code status} "ok", "warn" or "fail". */
    public record Check(String key, String label, String status, String detail, String url) {
    }

    public record Report(Instant checkedAt, List<Check> checks, BigDecimal costUsd) {
    }

    private final AiVisibilitySettingsRepository settingsRepo;
    private final AiVisibilityQueryRepository queries;
    private final PageFetcher pageFetcher;
    private final DataForSeoHttp dataForSeo;
    private final JdbcTemplate jdbc;
    private final TelegramNotificationService telegram;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10)).build();

    public AiReadinessService(AiVisibilitySettingsRepository settingsRepo, AiVisibilityQueryRepository queries,
                              PageFetcher pageFetcher, DataForSeoHttp dataForSeo, JdbcTemplate jdbc,
                              TelegramNotificationService telegram) {
        this.settingsRepo = settingsRepo;
        this.queries = queries;
        this.pageFetcher = pageFetcher;
        this.dataForSeo = dataForSeo;
        this.jdbc = jdbc;
        this.telegram = telegram;
    }

    @Scheduled(cron = "0 30 5 * * MON", zone = "America/Los_Angeles")
    @SchedulerLock(name = "AiReadinessService_weekly", lockAtLeastFor = "PT1M", lockAtMostFor = "PT1H")
    public void weekly() {
        for (AiVisibilitySettings s : settingsRepo.findAll()) {
            if (s.getOwnDomains() == null || s.getOwnDomains().isEmpty()) continue;
            try {
                runAndStore(s.getBusinessId());
            } catch (RuntimeException e) {
                log.warn("AI readiness check failed for business {}: {}", s.getBusinessId(), e.getMessage(), e);
            }
        }
    }

    public Report runAndStore(Long businessId) {
        Optional<Report> previous = latest(businessId);
        Report report = run(businessId);
        jdbc.update("INSERT INTO ai_readiness_report (business_id, checks, cost_usd) VALUES (?, ?::jsonb, ?)",
                businessId, toJson(report.checks()), report.costUsd());
        List<Check> newlyFailed = report.checks().stream().filter(c -> "fail".equals(c.status()))
                .filter(c -> previous.map(p -> p.checks().stream().noneMatch(o -> o.key().equals(c.key()) && "fail".equals(o.status())))
                        .orElse(true))
                .toList();
        if (!newlyFailed.isEmpty()) {
            StringBuilder sb = new StringBuilder("🤖 AI readiness: new problems on our site\n");
            for (Check c : newlyFailed) sb.append("• ").append(c.label()).append(": ").append(c.detail()).append('\n');
            sb.append("Marketing → AI → AI readiness");
            telegram.sendOwnerAlert(businessId, sb.toString().trim());
        }
        return report;
    }

    public Optional<Report> latest(Long businessId) {
        return jdbc.query("SELECT checked_at, checks, cost_usd FROM ai_readiness_report WHERE business_id = ? ORDER BY checked_at DESC LIMIT 1",
                (rs, i) -> new Report(rs.getTimestamp("checked_at").toInstant(), fromJson(rs.getString("checks")),
                        rs.getBigDecimal("cost_usd")), businessId).stream().findFirst();
    }

    Report run(Long businessId) {
        AiVisibilitySettings settings = settingsRepo.findById(businessId).orElseThrow();
        List<Check> checks = new ArrayList<>();
        BigDecimal cost = BigDecimal.ZERO;
        for (String domain : settings.getOwnDomains()) {
            String base = "https://" + domain;
            Fetched robots = fetchText(base + "/robots.txt");
            cost = cost.add(robots.cost());
            checks.add(robotsCheck(domain, robots));
            Fetched llms = fetchText(base + "/llms.txt");
            cost = cost.add(llms.cost());
            checks.add(llmsCheck(domain, llms));

            Set<String> pages = new LinkedHashSet<>();
            pages.add(base + "/");
            for (AiVisibilityQuery q : queries.findByBusinessIdAndStatusInOrderBySortOrderAscIdAsc(businessId,
                    List.of(AiVisibilityQuery.Status.ACTIVE))) {
                String u = q.getTargetUrl();
                if (u != null && hostOf(u).endsWith(domain)) pages.add(u);
            }
            for (String url : pages.stream().limit(MAX_PAGES).toList()) {
                // What a crawler gets without running JavaScript: directly, or through DataForSEO
                // (no JS) when this server is refused; JS rendering only to tell "needs JS" apart
                // from a genuinely short page.
                PageFetcher.Fetched f = pageFetcher.fetch(url, false);
                if (f.snapshot() == null && f.httpStatus() != 404 && f.httpStatus() != 410) {
                    f = pageFetcher.onPage(url, false, f.httpStatus());
                    cost = cost.add(f.cost());
                }
                boolean needsJs = false;
                if (f.snapshot() != null && f.snapshot().wordCount() < MIN_WORDS_NO_JS) {
                    PageFetcher.Fetched js = pageFetcher.onPage(url, true, f.httpStatus());
                    cost = cost.add(js.cost());
                    needsJs = js.snapshot() != null && js.snapshot().wordCount() >= MIN_WORDS_NO_JS;
                }
                checks.add(pageCheck(url, f, needsJs));
            }
        }
        return new Report(Instant.now(), checks, cost);
    }

    static Check robotsCheck(String domain, Fetched f) {
        String key = "robots:" + domain;
        String label = domain + "/robots.txt";
        if (f.text() == null) return new Check(key, label, "warn", "Could not read it (" + f.error() + ")", "https://" + domain + "/robots.txt");
        if (looksLikeHtml(f.text())) return new Check(key, label, "warn", "Returns a web page instead of a robots.txt file", "https://" + domain + "/robots.txt");
        List<String> blocked = blockedBots(f.text());
        return blocked.isEmpty()
                ? new Check(key, label, "ok", "Open to AI and search crawlers", "https://" + domain + "/robots.txt")
                : new Check(key, label, "fail", "Closed to " + String.join(", ", blocked), "https://" + domain + "/robots.txt");
    }

    static Check llmsCheck(String domain, Fetched f) {
        String key = "llms:" + domain;
        String label = domain + "/llms.txt";
        String url = "https://" + domain + "/llms.txt";
        if (f.text() == null || f.text().isBlank()) return new Check(key, label, "warn", "Missing (" + f.error() + ")", url);
        if (looksLikeHtml(f.text())) return new Check(key, label, "warn", "Returns a web page instead of a text file", url);
        int bytes = f.text().getBytes(StandardCharsets.UTF_8).length;
        return bytes < MIN_LLMS_BYTES
                ? new Check(key, label, "warn", "Only " + bytes + " bytes: add the services, prices, FAQ", url)
                : new Check(key, label, "ok", String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0), url);
    }

    static Check pageCheck(String url, PageFetcher.Fetched f, boolean needsJs) {
        String key = "page:" + url;
        String label = pathOf(url);
        if (f.snapshot() == null) {
            return new Check(key, label, "fail", "Not readable: " + (f.error() == null ? "HTTP " + f.httpStatus() : f.error()), url);
        }
        PageSnapshot s = f.snapshot();
        List<String> problems = new ArrayList<>();
        if (needsJs) problems.add("the text appears only after JavaScript runs");
        if (s.wordCount() < MIN_WORDS_NO_JS) problems.add("only " + s.wordCount() + " words of text");
        boolean hasH1 = s.headings().stream().anyMatch(h -> h.startsWith("H1|"));
        if (!hasH1) problems.add("no H1 heading");
        if (s.jsonLd().isEmpty()) problems.add("no structured data (JSON-LD)");
        if (problems.isEmpty()) return new Check(key, label, "ok", s.wordCount() + " words, H1, structured data", url);
        return new Check(key, label, needsJs || s.wordCount() < MIN_WORDS_NO_JS ? "fail" : "warn", String.join("; ", problems), url);
    }

    /** Bots from AI_BOTS that a "Disallow: /" applies to (their own group, or "*" when they have
     * none). */
    static List<String> blockedBots(String robots) {
        Map<String, Boolean> groupBlocksAll = new LinkedHashMap<>();
        List<String> current = new ArrayList<>();
        boolean lastWasAgent = false;
        for (String raw : robots.split("\\r?\\n")) {
            String line = raw.replaceAll("#.*", "").trim();
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String field = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (field.equals("user-agent")) {
                if (!lastWasAgent) current = new ArrayList<>();
                current.add(value.toLowerCase(Locale.ROOT));
                groupBlocksAll.putIfAbsent(value.toLowerCase(Locale.ROOT), false);
                lastWasAgent = true;
            } else {
                lastWasAgent = false;
                if (field.equals("disallow") && value.equals("/")) for (String ua : current) groupBlocksAll.put(ua, true);
                if (field.equals("allow") && value.equals("/")) for (String ua : current) groupBlocksAll.put(ua, false);
            }
        }
        List<String> out = new ArrayList<>();
        for (String bot : AI_BOTS) {
            Boolean own = groupBlocksAll.get(bot.toLowerCase(Locale.ROOT));
            boolean blocked = own != null ? own : groupBlocksAll.getOrDefault("*", false);
            if (blocked) out.add(bot);
        }
        return out;
    }

    record Fetched(String text, BigDecimal cost, String error) {
    }

    /** A small text file: straight from here, or through DataForSEO when this server is refused. */
    Fetched fetchText(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                    .header("User-Agent", PageFetcher.USER_AGENT).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() >= 200 && res.statusCode() < 300) return new Fetched(res.body(), BigDecimal.ZERO, null);
            if (res.statusCode() == 404) return new Fetched(null, BigDecimal.ZERO, "HTTP 404");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Fetched(null, BigDecimal.ZERO, "Interrupted");
        } catch (Exception e) {
            // fall through to DataForSEO
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("url", url);
            payload.put("enable_javascript", false);
            payload.put("custom_js", TEXT_JS);
            JsonNode task = dataForSeo.post("on_page/instant_pages", payload);
            BigDecimal cost = task.path("cost").isNumber() ? task.path("cost").decimalValue() : BigDecimal.ZERO;
            JsonNode item = task.path("result").path(0).path("items").path(0);
            int status = item.path("status_code").asInt(0);
            String text = item.path("custom_js_response").asText(null);
            if (status >= 200 && status < 300 && text != null) return new Fetched(text, cost, null);
            return new Fetched(null, cost, "HTTP " + status);
        } catch (Exception e) {
            return new Fetched(null, BigDecimal.ZERO, "Not reachable");
        }
    }

    static boolean looksLikeHtml(String text) {
        String t = text.stripLeading().toLowerCase(Locale.ROOT);
        return t.startsWith("<!doctype html") || t.startsWith("<html");
    }

    private static String hostOf(String url) {
        try {
            String h = URI.create(url).getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static String pathOf(String url) {
        try {
            URI u = URI.create(url);
            return u.getHost() + (u.getPath() == null || u.getPath().isEmpty() ? "/" : u.getPath());
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    private String toJson(List<Check> checks) {
        try {
            return json.writeValueAsString(checks);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Check> fromJson(String s) {
        try {
            return json.readValue(s, new TypeReference<List<Check>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
