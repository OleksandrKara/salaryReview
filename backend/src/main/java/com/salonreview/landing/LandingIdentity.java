package com.salonreview.landing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Cookie-backed visitor id and A/B variant, byte-compatible with what the former salonLandings
 * (Starlette + http.cookies) wrote and read, because both backends serve the same browsers while
 * traffic is split between them.
 */
public final class LandingIdentity {

    public static final String VISITOR_COOKIE = "mani_vid";
    public static final String VARIANT_COOKIE = "mani_variant";
    private static final long MAX_AGE = 400L * 24 * 3600;
    private static final String LEGAL = "!#$%&'*+-.^_`|~:";
    private static final String UNESCAPED = " ()/<=>?@[]{}";
    private static final Pattern OCTAL = Pattern.compile("\\\\[0-3][0-7][0-7]");
    private static final Pattern QUOTE = Pattern.compile("\\\\.", Pattern.DOTALL);
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Assignment(String landingPageId, String variantId) {
    }

    private LandingIdentity() {
    }

    private static boolean legal(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || LEGAL.indexOf(c) >= 0;
    }

    /** http.cookies._quote */
    static String quote(String value) {
        boolean all = true;
        for (int i = 0; i < value.length(); i++) {
            if (!legal(value.charAt(i))) {
                all = false;
                break;
            }
        }
        if (all) return value;
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') sb.append("\\\"");
            else if (c == '\\') sb.append("\\\\");
            else if (c < 256 && !legal(c) && UNESCAPED.indexOf(c) < 0) sb.append(String.format("\\%03o", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    /** http.cookies._unquote */
    static String unquote(String s) {
        if (s == null || s.length() < 2) return s;
        if (s.charAt(0) != '"' || s.charAt(s.length() - 1) != '"') return s;
        String str = s.substring(1, s.length() - 1);
        StringBuilder res = new StringBuilder();
        int i = 0;
        int n = str.length();
        while (i >= 0 && i < n) {
            Matcher o = OCTAL.matcher(str);
            Matcher q = QUOTE.matcher(str);
            boolean oFound = o.find(i);
            boolean qFound = q.find(i);
            if (!oFound && !qFound) {
                res.append(str, i, n);
                break;
            }
            int j = oFound ? o.start() : -1;
            int k = qFound ? q.start() : -1;
            if (qFound && (!oFound || k < j)) {
                res.append(str, i, k).append(str.charAt(k + 1));
                i = k + 2;
            } else {
                res.append(str, i, j).append((char) Integer.parseInt(str.substring(j + 1, j + 4), 8));
                i = j + 4;
            }
        }
        return res.toString();
    }

    /** Starlette cookie_parser over every Cookie header; later duplicates win. */
    static Map<String, String> parseCookies(HttpServletRequest request) {
        Map<String, String> out = new LinkedHashMap<>();
        java.util.Enumeration<String> headers = request.getHeaders("Cookie");
        List<String> all = new ArrayList<>();
        while (headers != null && headers.hasMoreElements()) all.add(headers.nextElement());
        for (String header : all) {
            for (String chunk : header.split(";", -1)) {
                String key;
                String val;
                int eq = chunk.indexOf('=');
                if (eq >= 0) {
                    key = chunk.substring(0, eq);
                    val = chunk.substring(eq + 1);
                } else {
                    key = "";
                    val = chunk;
                }
                key = LandingText.strip(key);
                val = LandingText.strip(val);
                if (!key.isEmpty() || !val.isEmpty()) out.put(key, unquote(val));
            }
        }
        return out;
    }

    static String setCookieHeader(String name, String value) {
        return name + "=" + quote(value) + "; HttpOnly; Max-Age=" + MAX_AGE + "; Path=/; SameSite=lax; Secure";
    }

    /** Python json.dumps default separators and ensure_ascii. */
    static String variantJson(String landingPageId, String variantId) {
        return "{\"landing_page_id\": " + jsonString(landingPageId) + ", \"variant_id\": " + jsonString(variantId) + "}";
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || c > 0x7e) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    public static String resolveVisitorId(Map<String, String> cookies, HttpServletResponse response, String clientVisitorId) {
        String cookieVid = cookies.get(VISITOR_COOKIE);
        if (cookieVid != null && !cookieVid.isEmpty()) return cookieVid;
        String resolved = clientVisitorId != null && !clientVisitorId.isEmpty() ? clientVisitorId : UUID.randomUUID().toString();
        response.addHeader("Set-Cookie", setCookieHeader(VISITOR_COOKIE, resolved));
        return resolved;
    }

    public static Assignment resolveVariant(Map<String, String> cookies, HttpServletResponse response,
                                            String clientLandingPageId, String clientVariantId) {
        if (clientLandingPageId != null && !clientLandingPageId.isEmpty()
                && clientVariantId != null && !clientVariantId.isEmpty()) {
            response.addHeader("Set-Cookie",
                    setCookieHeader(VARIANT_COOKIE, variantJson(clientLandingPageId, clientVariantId)));
            return new Assignment(clientLandingPageId, clientVariantId);
        }
        String raw = cookies.get(VARIANT_COOKIE);
        if (raw == null || raw.isEmpty()) return new Assignment(clientLandingPageId, clientVariantId);
        try {
            JsonNode node = JSON.readTree(raw);
            if (node == null || !node.isObject()) return new Assignment(clientLandingPageId, clientVariantId);
            return new Assignment(text(node.get("landing_page_id")), text(node.get("variant_id")));
        } catch (Exception e) {
            return new Assignment(clientLandingPageId, clientVariantId);
        }
    }

    private static String text(JsonNode n) {
        return n == null || n.isNull() ? null : n.asText();
    }
}
