package com.salonreview.aivisibility;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Fetches one page for the comparison (design.md D10): a plain GET first (free), respecting
 * robots.txt and at most one request per domain every 2 seconds; when that is blocked (403/429/5xx)
 * or returns an empty JS shell, DataForSEO On-Page with our compact {@code custom_js}
 * ($0.00015 per page, $0.0015 with JS rendering, measured 2026-10-12). */
@Component
public class PageFetcher {

    static final int MIN_WORDS = 300;
    static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Safari/537.36";
    private static final int MAX_BYTES = 3_000_000;

    public record Fetched(PageSnapshot snapshot, int httpStatus, String method, BigDecimal cost, String error) {
        boolean readable() {
            return snapshot != null;
        }
    }

    private final DataForSeoHttp dataForSeo;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Long> lastHit = new ConcurrentHashMap<>();
    private final Map<String, List<String>> robotsCache = new ConcurrentHashMap<>();

    public PageFetcher(DataForSeoHttp dataForSeo) {
        this.dataForSeo = dataForSeo;
    }

    public Fetched fetch(String url, boolean allowPaidFallback) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return new Fetched(null, 0, null, BigDecimal.ZERO, "Bad URL");
        }
        if (!robotsAllows(uri)) return new Fetched(null, 0, null, BigDecimal.ZERO, "Disallowed by robots.txt");
        int status = 0;
        try {
            throttle(uri.getHost());
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml").GET().build();
            HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            status = res.statusCode();
            if (status >= 200 && status < 300) {
                byte[] body = res.body();
                String html = new String(body, 0, Math.min(body.length, MAX_BYTES), StandardCharsets.UTF_8);
                PageSnapshot snap = PageSnapshot.fromHtml(html, url);
                if (snap.wordCount() >= MIN_WORDS || !allowPaidFallback) return new Fetched(snap, status, "DIRECT", BigDecimal.ZERO, null);
                // Maybe a JS shell; but a genuinely short page (our /russian-pedicure, ~270 words) is
                // fine as fetched, so keep the direct read unless rendering gives more.
                Fetched rendered = onPage(url, true, status);
                return rendered.readable() && rendered.snapshot().wordCount() > snap.wordCount() ? rendered
                        : new Fetched(snap, status, "DIRECT", rendered.cost(), null);
            }
            if (status == 404 || status == 410) return new Fetched(null, status, "DIRECT", BigDecimal.ZERO, "Page not found");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Fetched(null, status, null, BigDecimal.ZERO, "Interrupted");
        } catch (Exception e) {
            if (!allowPaidFallback) return new Fetched(null, status, "DIRECT", BigDecimal.ZERO, e.getClass().getSimpleName());
        }
        if (!allowPaidFallback) return new Fetched(null, status, "DIRECT", BigDecimal.ZERO, "HTTP " + status);
        Fetched plain = onPage(url, false, status);
        if (plain.readable() && plain.snapshot().wordCount() < MIN_WORDS) {
            Fetched rendered = onPage(url, true, status);
            return new Fetched(rendered.snapshot() != null ? rendered.snapshot() : plain.snapshot(), rendered.httpStatus(), rendered.method(),
                    plain.cost().add(rendered.cost()), rendered.error());
        }
        return plain;
    }

    private Fetched onPage(String url, boolean javascript, int directStatus) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("url", url);
        payload.put("enable_javascript", javascript);
        payload.put("custom_js", PageSnapshot.CUSTOM_JS);
        try {
            JsonNode task = dataForSeo.post("on_page/instant_pages", payload);
            BigDecimal cost = task.path("cost").isNumber() ? task.path("cost").decimalValue() : BigDecimal.ZERO;
            JsonNode item = task.path("result").path(0).path("items").path(0);
            int status = item.path("status_code").asInt(directStatus);
            String js = item.path("custom_js_response").asText(null);
            String method = javascript ? "ONPAGE_JS" : "ONPAGE";
            if (js == null || js.isBlank() || !js.trim().startsWith("{")) return new Fetched(null, status, method, cost, "No content (HTTP " + status + ")");
            return new Fetched(PageSnapshot.fromCustomJs(json.readTree(js)), status, method, cost, null);
        } catch (Exception e) {
            return new Fetched(null, directStatus, javascript ? "ONPAGE_JS" : "ONPAGE", BigDecimal.ZERO, "Fallback failed: " + e.getMessage());
        }
    }

    private void throttle(String host) throws InterruptedException {
        long now = System.currentTimeMillis();
        Long prev = lastHit.put(host, now);
        if (prev != null && now - prev < 2000) Thread.sleep(2000 - (now - prev));
    }

    /** Minimal robots.txt check: Disallow prefixes in the "User-agent: *" group. Unreachable robots
     * means allowed, as crawlers do. */
    boolean robotsAllows(URI uri) {
        String origin = uri.getScheme() + "://" + uri.getHost();
        List<String> disallow = robotsCache.computeIfAbsent(origin, o -> {
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(o + "/robots.txt")).timeout(Duration.ofSeconds(8))
                        .header("User-Agent", USER_AGENT).GET().build(), HttpResponse.BodyHandlers.ofString());
                return r.statusCode() == 200 ? parseDisallow(r.body()) : List.of();
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                return List.of();
            }
        });
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        return disallow.stream().noneMatch(path::startsWith);
    }

    static List<String> parseDisallow(String robots) {
        List<String> out = new ArrayList<>();
        boolean inStar = false;
        boolean lastWasAgent = false;
        for (String raw : robots.split("\\r?\\n")) {
            String line = raw.replaceAll("#.*", "").trim();
            if (line.isEmpty()) continue;
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("user-agent:")) {
                String agent = line.substring(11).trim();
                inStar = (lastWasAgent && inStar) || agent.equals("*");
                lastWasAgent = true;
                continue;
            }
            lastWasAgent = false;
            if (inStar && lower.startsWith("disallow:")) {
                String p = line.substring(9).trim();
                if (!p.isEmpty() && !p.contains("*")) out.add(p);
            }
        }
        return out;
    }
}
