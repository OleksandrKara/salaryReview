package com.salonreview.aivisibility;

import com.salonreview.aivisibility.PageComponentExtractor.Component;
import com.salonreview.config.AiVisibilityProperties;
import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.AiVisibilityCheck;
import com.salonreview.domain.AiVisibilityPage;
import com.salonreview.domain.AiVisibilityQuery;
import com.salonreview.domain.AiVisibilityRun;
import com.salonreview.domain.AiVisibilitySettings;
import com.salonreview.repo.AiVisibilityCheckRepository;
import com.salonreview.repo.AiVisibilityPageRepository;
import com.salonreview.repo.AiVisibilityQueryRepository;
import com.salonreview.repo.AiVisibilityRunRepository;
import com.salonreview.repo.AiVisibilitySettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Page comparison (design.md D10): which competitor pages the assistants cite for a question, what
 * components they have that our target page lacks. Fetching is monthly (and on demand, once a day per
 * question); reads are computed from the stored snapshots. */
@Service
public class AiVisibilityPageService {

    private static final Logger log = LoggerFactory.getLogger(AiVisibilityPageService.class);
    static final int MAX_COMPETITOR_PAGES = 5;
    static final int MONTHLY_FETCH_CAP = 40;
    static final Duration CACHE = Duration.ofDays(28);
    static final Duration SOURCE_WINDOW = Duration.ofDays(28);
    /** Directories, social networks, maps and media: tracked as cited sources, not compared as pages. */
    static final Set<String> NOT_A_COMPETITOR_PAGE = Set.of("yelp.com", "google.com", "maps.google.com", "facebook.com", "instagram.com",
            "tiktok.com", "youtube.com", "reddit.com", "booksy.com", "vagaro.com", "fresha.com", "styleseat.com", "groupon.com",
            "yellowpages.com", "bbb.org", "expertise.com", "threebestrated.com", "tripadvisor.com", "nextdoor.com", "apple.com",
            "bing.com", "wikipedia.org", "mapquest.com", "squareup.com", "glossgenius.com", "heygoldie.com", "linkedin.com",
            "pinterest.com", "x.com", "twitter.com", "sayheysandiego.com", "sdvoyager.com", "timeout.com", "whowhatwear.com",
            "allure.com", "cosmopolitan.com", "healthline.com", "byrdie.com", "realself.com", "thumbtack.com", "angi.com");

    public record PageView(String url, boolean own, Instant lastFetchedAt, Integer httpStatus, String fetchMethod, boolean readable,
            boolean businessPage, Map<String, Map<String, Object>> components, Map<String, Object> metrics, String error, int citedTimes) {
    }

    public record Gap(String component, String label, int competitorsWith, int competitorsCompared, boolean weak) {
    }

    public record Comparison(Long queryId, String query, String targetUrl, PageView ours, List<PageView> competitors, List<Gap> gaps,
            Instant lastRefreshedAt) {
    }

    public record PageGap(String targetUrl, String component, String label, int competitorsWith, int competitorsCompared, List<String> queries) {
    }

    private final AiVisibilityPageRepository pages;
    private final AiVisibilityQueryRepository queries;
    private final AiVisibilityCheckRepository checks;
    private final AiVisibilityRunRepository runs;
    private final AiVisibilitySettingsRepository settingsRepo;
    private final AiVisibilityRunService runService;
    private final AiVisibilityProperties props;
    private final PageFetcher fetcher;
    private final CurrentBusinessContext currentBusinessContext;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ai-visibility-pages");
        t.setDaemon(true);
        return t;
    });

    public AiVisibilityPageService(AiVisibilityPageRepository pages, AiVisibilityQueryRepository queries, AiVisibilityCheckRepository checks,
            AiVisibilityRunRepository runs, AiVisibilitySettingsRepository settingsRepo, AiVisibilityRunService runService,
            AiVisibilityProperties props, PageFetcher fetcher, CurrentBusinessContext currentBusinessContext) {
        this.pages = pages;
        this.queries = queries;
        this.checks = checks;
        this.runs = runs;
        this.settingsRepo = settingsRepo;
        this.runService = runService;
        this.props = props;
        this.fetcher = fetcher;
        this.currentBusinessContext = currentBusinessContext;
    }

    /** Competitor page URLs for a question, most cited first (last 4 weeks of OK checks). A cited URL
     * counts only when its domain matches a place the assistants named for this question (list and
     * directory pages stay in "sources where we're missing" instead), and never when it belongs to
     * any business on this platform (the owner's other business is not a competitor). */
    LinkedHashMap<String, Integer> competitorUrls(Long businessId, Long queryId, List<String> ownDomains) {
        Instant from = Instant.now().minus(SOURCE_WINDOW);
        List<String> platformDomains = new ArrayList<>(ownDomains);
        for (AiVisibilitySettings other : settingsRepo.findAll()) platformDomains.addAll(other.getOwnDomains());
        List<AiVisibilityCheck> recent = checks.findByBusinessIdAndQueryIdOrderByCheckedAtDesc(businessId, queryId).stream()
                .filter(c -> c.getStatus() == AiVisibilityCheck.Status.OK && !c.getCheckedAt().isBefore(from)).toList();
        Set<String> places = new LinkedHashSet<>();
        for (AiVisibilityCheck c : recent) places.addAll(c.getPlacesNamed());
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Instant> latest = new HashMap<>();
        for (AiVisibilityCheck c : recent) {
            for (Map<String, String> s : c.getSourcesCited()) {
                String url = normalizeUrl(s.get("url"));
                String domain = s.get("domain");
                if (url == null || domain == null || MentionDetector.isOwnDomain(domain, platformDomains) || isDirectory(domain)) continue;
                if (!domainMatchesPlace(domain, places)) continue;
                counts.merge(url, 1, Integer::sum);
                latest.merge(url, c.getCheckedAt(), (a, b) -> a.isAfter(b) ? a : b);
            }
        }
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(e -> latest.get(e.getKey()), Comparator.reverseOrder()))
                .limit(MAX_COMPETITOR_PAGES)
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    /** "avobrowsandbeauty.com" ~ "AVO Brows & Beauty", "polinailssd.com" ~ "Polinails",
     * "svitnail.com" ~ "Svitnail Studio". Letters/digits only, at least 5 shared characters. */
    static boolean domainMatchesPlace(String domain, Set<String> places) {
        String host = domain.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        String label = host.contains(".") ? host.substring(0, host.lastIndexOf('.')) : host;
        label = label.replaceAll("[^a-z0-9]", "");
        if (label.length() < 4) return false;
        for (String p : places) {
            String name = p.toLowerCase(Locale.ROOT).replaceAll("&", "and").replaceAll("[^a-z0-9]", "");
            String firstWord = p.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")[0];
            if (name.length() < 4) continue;
            if (label.contains(name) || (name.length() >= 5 && name.contains(label))) return true;
            if (firstWord.length() >= 5 && label.startsWith(firstWord)) return true;
        }
        return false;
    }

    static boolean isDirectory(String domain) {
        String d = domain.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        for (String x : NOT_A_COMPETITOR_PAGE) if (d.equals(x) || d.endsWith("." + x)) return true;
        return false;
    }

    static String normalizeUrl(String url) {
        if (url == null || url.isBlank() || !url.startsWith("http")) return null;
        String u = url.replaceAll("[?&]utm_[^&#]*", "").replaceAll("\\?$", "").replaceAll("#.*$", "");
        return u.contains("vertexaisearch") ? null : u;
    }

    public Comparison comparison(Long businessId, Long queryId) {
        AiVisibilityQuery q = queries.findByIdAndBusinessId(queryId, businessId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<String> ownDomains = settingsRepo.findById(businessId).map(AiVisibilitySettings::getOwnDomains).orElse(List.of());
        LinkedHashMap<String, Integer> comp = competitorUrls(businessId, queryId, ownDomains);
        List<String> urls = new ArrayList<>(comp.keySet());
        if (q.getTargetUrl() != null) urls.add(q.getTargetUrl());
        Map<String, AiVisibilityPage> stored = new HashMap<>();
        for (AiVisibilityPage p : pages.findByBusinessIdAndUrlIn(businessId, urls)) stored.put(p.getUrl(), p);

        PageView ours = q.getTargetUrl() == null ? null : view(stored.get(q.getTargetUrl()), q.getTargetUrl(), true, 0);
        List<PageView> competitors = new ArrayList<>();
        comp.forEach((url, n) -> competitors.add(view(stored.get(url), url, false, n)));
        Instant last = stored.values().stream().map(AiVisibilityPage::getLastFetchedAt).filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        return new Comparison(q.getId(), q.getQueryText(), q.getTargetUrl(), ours, competitors, gaps(ours, competitors), last);
    }

    static List<Gap> gaps(PageView ours, List<PageView> competitors) {
        List<Gap> out = new ArrayList<>();
        if (ours == null || !ours.readable()) return out;
        List<PageView> compared = competitors.stream().filter(c -> c.readable() && c.businessPage()).toList();
        if (compared.isEmpty()) return out;
        for (Component c : Component.values()) {
            if (present(ours, c)) continue;
            int with = (int) compared.stream().filter(p -> present(p, c)).count();
            boolean weak = compared.size() == 1;
            if (with >= 2 || (weak && with == 1)) out.add(new Gap(c.name(), c.label, with, compared.size(), weak));
        }
        out.sort(Comparator.comparingDouble((Gap g) -> -(double) g.competitorsWith() / g.competitorsCompared()));
        return out;
    }

    private static boolean present(PageView p, Component c) {
        Map<String, Object> f = p.components().get(c.name());
        return f != null && Boolean.TRUE.equals(f.get("present"));
    }

    private static PageView view(AiVisibilityPage p, String url, boolean own, int cited) {
        if (p == null || p.getLastFetchedAt() == null) return new PageView(url, own, null, null, null, false, false, Map.of(), Map.of(), null, cited);
        boolean readable = !p.getComponents().isEmpty();
        boolean business = Boolean.TRUE.equals(p.getMetrics().get("businessPage"));
        return new PageView(url, own, p.getLastFetchedAt(), p.getHttpStatus(), p.getFetchMethod(), readable, business, p.getComponents(), p.getMetrics(), p.getError(), cited);
    }

    /** Roll-up for the AI tab: gaps per our target page across questions, non-weak only. */
    public List<PageGap> pageGaps(Long businessId) {
        Map<String, PageGap> merged = new LinkedHashMap<>();
        for (AiVisibilityQuery q : queries.findByBusinessIdAndStatusInOrderBySortOrderAscIdAsc(businessId, List.of(AiVisibilityQuery.Status.ACTIVE))) {
            if (q.getTargetUrl() == null) continue;
            Comparison c = comparison(businessId, q.getId());
            for (Gap g : c.gaps()) {
                if (g.weak()) continue;
                String key = q.getTargetUrl() + "|" + g.component();
                PageGap prev = merged.get(key);
                List<String> qs = new ArrayList<>(prev == null ? List.of() : prev.queries());
                qs.add(q.getQueryText());
                int with = Math.max(g.competitorsWith(), prev == null ? 0 : prev.competitorsWith());
                int of = Math.max(g.competitorsCompared(), prev == null ? 0 : prev.competitorsCompared());
                merged.put(key, new PageGap(q.getTargetUrl(), g.component(), g.label(), with, of, qs));
            }
        }
        return merged.values().stream()
                .sorted(Comparator.comparingDouble((PageGap g) -> -(g.queries().size() * (double) g.competitorsWith() / g.competitorsCompared())))
                .toList();
    }

    /** Owner "Refresh pages" for one question: once per PT day, in the background. */
    public void startRefresh(Long businessId, Long queryId) {
        AiVisibilityQuery q = queries.findByIdAndBusinessId(queryId, businessId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Instant todayPt = LocalDate.now(AiVisibilityRunService.PT).atStartOfDay(AiVisibilityRunService.PT).toInstant();
        if (q.getTargetUrl() != null && pages.findByBusinessIdAndUrl(businessId, q.getTargetUrl())
                .map(AiVisibilityPage::getLastFetchedAt).filter(t -> t != null && t.isAfter(todayPt)).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Pages for this question were already refreshed today");
        }
        executor.submit(() -> currentBusinessContext.runAs(businessId, () -> {
            try {
                refresh(businessId, List.of(q), true);
            } catch (RuntimeException e) {
                log.warn("Page refresh failed for business {} query {}: {}", businessId, queryId, e.toString());
            }
        }));
    }

    /** Monthly pass over every active question (first Monday run of the month). */
    public void refreshAll(Long businessId) {
        refresh(businessId, queries.findByBusinessIdAndStatusInOrderBySortOrderAscIdAsc(businessId, List.of(AiVisibilityQuery.Status.ACTIVE)), false);
    }

    void refresh(Long businessId, List<AiVisibilityQuery> qs, boolean onDemand) {
        AiVisibilitySettings settings = settingsRepo.findById(businessId).orElse(null);
        if (settings == null) return;
        Instant monthStart = LocalDate.now(AiVisibilityRunService.PT).withDayOfMonth(1).atStartOfDay(AiVisibilityRunService.PT).toInstant();
        long fetchedThisMonth = pages.countByBusinessIdAndLastFetchedAtAfterAndFetchMethodIsNotNull(businessId, monthStart);
        AiVisibilityRun run = null;
        Set<String> done = new LinkedHashSet<>();
        for (AiVisibilityQuery q : qs) {
            List<String[]> targets = new ArrayList<>();
            if (q.getTargetUrl() != null) targets.add(new String[]{q.getTargetUrl(), "own"});
            for (String url : competitorUrls(businessId, q.getId(), settings.getOwnDomains()).keySet()) targets.add(new String[]{url, "competitor"});
            for (String[] t : targets) {
                String url = t[0];
                boolean own = t[1].equals("own");
                if (!done.add(url)) continue;
                AiVisibilityPage page = pages.findByBusinessIdAndUrl(businessId, url).orElseGet(() ->
                        AiVisibilityPage.builder().businessId(businessId).url(url).own(own).build());
                boolean fresh = page.getLastFetchedAt() != null && page.getLastFetchedAt().isAfter(Instant.now().minus(CACHE));
                if (fresh && !(onDemand && own)) continue;
                if (fetchedThisMonth >= MONTHLY_FETCH_CAP) {
                    log.info("Page fetch cap reached for business {} this month", businessId);
                    return;
                }
                boolean canPay = props.hasCredentials()
                        && runService.monthSpend(businessId).add(new BigDecimal("0.002")).compareTo(settings.getMonthlyBudgetUsd()) <= 0;
                PageFetcher.Fetched f = fetcher.fetch(url, canPay);
                fetchedThisMonth++;
                if (f.cost() != null && f.cost().signum() > 0) {
                    if (run == null) run = runs.save(AiVisibilityRun.builder().businessId(businessId).trigger(AiVisibilityRun.Trigger.PAGE_FETCH).build());
                    run.setCostUsd(run.getCostUsd().add(f.cost()));
                    runs.save(run);
                }
                store(page, f);
            }
        }
        if (run != null) {
            run.setFinishedAt(Instant.now());
            runs.save(run);
        }
    }

    private void store(AiVisibilityPage page, PageFetcher.Fetched f) {
        Map<String, Map<String, Object>> previous = page.getComponents();
        page.setLastFetchedAt(Instant.now());
        page.setHttpStatus(f.httpStatus());
        page.setFetchMethod(f.method() == null ? "DIRECT" : f.method());
        page.setError(f.error());
        if (f.readable()) {
            PageComponentExtractor.Result r = PageComponentExtractor.extract(f.snapshot());
            Map<String, Map<String, Object>> comps = new LinkedHashMap<>();
            r.components().forEach((k, v) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("present", v.present());
                if (v.evidence() != null) m.put("evidence", v.evidence());
                comps.put(k.name(), m);
            });
            Map<String, Object> metrics = new LinkedHashMap<>(r.metrics());
            metrics.put("businessPage", r.businessPage());
            String hash = Integer.toHexString(comps.toString().hashCode());
            if (previous != null && !previous.isEmpty() && !hash.equals(page.getContentHash())) {
                List<Map<String, Object>> hist = new ArrayList<>(page.getComponentsHistory());
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("until", Instant.now().toString());
                entry.put("components", previous);
                hist.add(0, entry);
                page.setComponentsHistory(new ArrayList<>(hist.subList(0, Math.min(6, hist.size()))));
            }
            page.setContentHash(hash);
            page.setComponents(comps);
            page.setMetrics(metrics);
        }
        // A failed refetch keeps the last readable snapshot; the error shows next to it.
        pages.save(page);
    }
}
