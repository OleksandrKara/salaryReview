package com.salonreview.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Turns a visitor IP into a short staff-facing location line for lead alerts, e.g.
 * "🇫🇷 France, Paris (IP 1.2.3.4)". Best-effort: a lookup failure falls back to the
 * Cloudflare-supplied country code, then to the bare IP. Never throws. */
@Service
public class GeoIpService {

    private static final Logger log = LoggerFactory.getLogger(GeoIpService.class);
    private static final int MAX_CACHE = 2000;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, String[]> cache = new ConcurrentHashMap<>();

    public String describe(String ip, String fallbackCountryCode) {
        String cleanIp = ip == null || ip.isBlank() ? null : ip.trim();
        String[] geo = cleanIp == null ? null : lookup(cleanIp);
        String code = geo != null ? geo[0] : normalizeCode(fallbackCountryCode);
        String country = geo != null ? geo[1] : null;
        String city = geo != null ? geo[2] : null;
        if (cleanIp == null && code == null) return null;

        StringBuilder sb = new StringBuilder();
        String flag = flag(code);
        if (!flag.isEmpty()) sb.append(flag).append(' ');
        if (country != null && !country.isBlank()) sb.append(country);
        else if (code != null) sb.append(code);
        if (city != null && !city.isBlank()) sb.append(", ").append(city);
        if (cleanIp != null) sb.append(sb.length() > 0 ? " (IP " : "IP ").append(cleanIp).append(sb.length() > 0 ? ")" : "");
        return sb.toString().trim();
    }

    private String[] lookup(String ip) {
        String[] hit = cache.get(ip);
        if (hit != null) return hit;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://ipwho.is/" + URLEncoder.encode(ip, StandardCharsets.UTF_8)
                            + "?fields=success,country,country_code,city"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) return null;
            JsonNode n = json.readTree(res.body());
            if (!n.path("success").asBoolean(false)) return null;
            String[] out = {normalizeCode(n.path("country_code").asText(null)), n.path("country").asText(null),
                    n.path("city").asText(null)};
            if (cache.size() >= MAX_CACHE) cache.clear();
            cache.put(ip, out);
            return out;
        } catch (Exception e) {
            log.warn("Geo lookup failed for lead alert (alert still sent): {}", e.toString());
            return null;
        }
    }

    static String normalizeCode(String c) {
        if (c == null) return null;
        String t = c.trim().toUpperCase();
        return t.matches("[A-Z]{2}") && !t.equals("XX") && !t.equals("T1") ? t : null;
    }

    static String flag(String code) {
        if (code == null || code.length() != 2) return "";
        StringBuilder sb = new StringBuilder();
        for (char ch : code.toCharArray()) sb.appendCodePoint(0x1F1E6 + (ch - 'A'));
        return sb.toString();
    }
}
