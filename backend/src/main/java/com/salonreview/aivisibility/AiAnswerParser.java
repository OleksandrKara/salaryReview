package com.salonreview.aivisibility;

import com.fasterxml.jackson.databind.JsonNode;
import com.salonreview.domain.AiVisibilityCheck.Engine;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns a DataForSEO result object into a {@link ParsedAnswer} (design.md D4). Deterministic: no
 * LLM call, so a check costs only the provider call. Shapes per engine were taken from real
 * responses on 2026-10-12 (test fixtures under src/test/resources/aivisibility). */
public final class AiAnswerParser {

    static final int MAX_PLACES = 10;
    static final int MAX_CITATIONS = 15;
    static final int MAX_SUB_QUERIES = 10;

    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*]\\([^)]*\\)");
    private static final Pattern BOLD = Pattern.compile("\\*\\*([^*\\n]{2,80})\\*\\*");
    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]+)]\\([^)]*\\)");
    // Labels models put in bold that are not business names (the VPS prototype's list, extended).
    private static final Pattern GENERIC = Pattern.compile(
            "^(specialt|location|address|phone|website|price|pricing|cost|services?\\b|rating|reviews?\\b|hours|why|best for|"
                    + "pros|cons|note|tip|what|how|key|top|expert|style|technique|experience|results?\\b|aftercare|consult|"
                    + "book|summary|bottom line|considerations?|recommend|highlights?|the vibe|vibe|known for|booking|"
                    + "cherry|afterpay|financing|important|option|my (first|top)|first choice|also|if you|here|quick|"
                    + "really|great|good|best |san diego|downtown|open|closed|call|directions|details|website|menu)",
            Pattern.CASE_INSENSITIVE);

    // A bold service name or a rating summary, not a business ("Russian manicures", "Highest Rated with Perfect Scores").
    private static final Pattern SERVICE_ONLY = Pattern.compile(
            "(?i)^(the )?(russian |japanese |gel |dry |e-file |classic |men's |mens )?(manicures?|pedicures?|nails?|nail (extensions?|art|salons?)|"
                    + "gel( nails| extensions| polish)?|extensions?|microblading|powder( brows)?|ombre brows|nano brows|lip blush(ing)?|"
                    + "permanent (makeup|eyeliner)|eyeliner|brows?|scar camouflage)$|(?i)^(one|two|three|a|an) (question|thing|tip|note)s?$|(?i)^(highest|top|best|most) .*");

    /** Google AI Mode / AI Overview local results come as plain lines, not bold: "Bespoke Brows 5.0
     * (124)Permanent make-up clinic" or, without reviews, "Luxe Ink Aesthetics Beauty salon".
     * The business type at the end marks the line as a place (2026-10-15: these answers had
     * produced no names at all, or junk like "Link" / "Details"). */
    private static final Pattern LOCAL_RESULT = Pattern.compile(
            "(?m)^\\s*(?:\\d+[.)]\\s*)?([^\\n]{3,80}?)\\s+(?:\\d\\.\\d\\s*\\(\\d[\\d,.Kk]*\\)\\s*)?"
                    + "(Permanent make-up clinic|Beauty salon|Nail salon|Tattoo shop|Tattoo and piercing shop|Eyebrow bar|Eyelash salon|"
                    + "Medical spa|Day spa|Spa|Skin care clinic|Facial spa|Hair salon|Beautician|Beauty school|Cosmetics store|"
                    + "Hair removal service|Waxing hair removal service|Threading service|Massage spa|Manicure service)\\s*$");

    private AiAnswerParser() {
    }

    public static ParsedAnswer parse(Engine engine, JsonNode result) {
        if (result == null || result.isNull()) return new ParsedAnswer("", List.of(), List.of(), List.of());
        return switch (engine) {
            case CHATGPT -> chatGpt(result);
            case GOOGLE_AI_MODE, GOOGLE_AI_OVERVIEW -> googleAiMode(result); // same ai_overview item shape
            case GEMINI, PERPLEXITY, CLAUDE -> llmResponse(result);
        };
    }

    private static ParsedAnswer chatGpt(JsonNode r) {
        String text = clean(r.path("markdown").asText(""));
        List<ParsedAnswer.Citation> citations = new ArrayList<>();
        for (JsonNode s : r.path("sources")) addCitation(citations, s.path("domain").asText(null), s.path("title").asText(null), s.path("url").asText(null));
        for (JsonNode s : r.path("search_results")) addCitation(citations, s.path("domain").asText(null), s.path("title").asText(null), s.path("url").asText(null));
        LinkedHashSet<String> places = new LinkedHashSet<>();
        for (JsonNode b : r.path("brand_entities")) {
            String cat = b.path("category").asText("");
            if (cat.isEmpty() || cat.contains("business")) addPlace(places, b.path("title").asText(""));
        }
        places.addAll(boldPlaces(text));
        return new ParsedAnswer(text, cap(citations, MAX_CITATIONS), cap(new ArrayList<>(places), MAX_PLACES), subQueries(r));
    }

    private static ParsedAnswer googleAiMode(JsonNode r) {
        StringBuilder text = new StringBuilder();
        List<ParsedAnswer.Citation> citations = new ArrayList<>();
        for (JsonNode item : r.path("items")) {
            if (!"ai_overview".equals(item.path("type").asText())) continue;
            text.append(item.path("markdown").asText("")).append('\n');
            for (JsonNode ref : item.path("references")) addCitation(citations, ref.path("domain").asText(null), ref.path("title").asText(null), ref.path("url").asText(null));
        }
        String t = clean(text.toString());
        LinkedHashSet<String> places = new LinkedHashSet<>(localResultPlaces(t));
        for (String p : boldPlaces(t)) if (places.stream().noneMatch(x -> x.equalsIgnoreCase(p))) places.add(p);
        return new ParsedAnswer(t, cap(citations, MAX_CITATIONS), cap(new ArrayList<>(places), MAX_PLACES), List.of());
    }

    private static ParsedAnswer llmResponse(JsonNode r) {
        StringBuilder text = new StringBuilder();
        List<ParsedAnswer.Citation> citations = new ArrayList<>();
        for (JsonNode item : r.path("items")) {
            for (JsonNode s : item.path("sections")) {
                text.append(s.path("text").asText("")).append('\n');
                for (JsonNode a : s.path("annotations")) {
                    String url = a.path("url").asText(null);
                    String title = a.path("title").asText(null);
                    String host = host(url);
                    // Gemini returns vertexaisearch redirect URLs; its title is the real domain.
                    String domain = host != null && host.contains("vertexaisearch") ? title : host;
                    addCitation(citations, domain, title, host != null && host.contains("vertexaisearch") ? null : url);
                }
            }
        }
        String t = clean(text.toString());
        return new ParsedAnswer(t, cap(citations, MAX_CITATIONS), cap(new ArrayList<>(boldPlaces(t)), MAX_PLACES), subQueries(r));
    }

    static List<String> localResultPlaces(String text) {
        LinkedHashSet<String> places = new LinkedHashSet<>();
        Matcher m = LOCAL_RESULT.matcher(text);
        while (m.find()) {
            // The business-type suffix already marks these lines as places, so the bold-text
            // heuristics (no long names, no generic words) don't apply: real names run long.
            String name = MD_LINK.matcher(m.group(1)).replaceAll("$1").replaceAll("[*\\\\]", "").replace("&amp;", "&").trim();
            if (name.length() < 3 || name.length() > 80 || name.split("\\s+").length > 12) continue;
            if (!Character.isUpperCase(name.codePointAt(0)) && !Character.isDigit(name.codePointAt(0))) continue;
            if (places.stream().noneMatch(p -> p.equalsIgnoreCase(name))) places.add(name);
        }
        return new ArrayList<>(places);
    }

    static List<String> boldPlaces(String text) {
        LinkedHashSet<String> places = new LinkedHashSet<>();
        Matcher m = BOLD.matcher(text);
        while (m.find()) addPlace(places, m.group(1));
        return new ArrayList<>(places);
    }

    private static void addPlace(Set<String> places, String raw) {
        String name = MD_LINK.matcher(raw).replaceAll("$1").replaceAll("\\\\", "").replace("&amp;", "&").trim();
        String cleaned = name.replaceAll("[:\\-–,.]+$", "").replaceAll("^\\d+[.)]\\s*", "").trim();
        if (cleaned.length() < 3 || cleaned.length() > 60) return;
        if (cleaned.matches(".*[0-9]{3,}.*") || cleaned.contains("·")) return;
        if (GENERIC.matcher(cleaned).find()) return;
        if (SERVICE_ONLY.matcher(cleaned).matches() || cleaned.matches(".*\\d+\\s*/\\s*\\d+.*")) return;
        // Business names start with a capital or a digit; bold lowercase text is a service ("powder brows").
        if (!Character.isUpperCase(cleaned.codePointAt(0)) && !Character.isDigit(cleaned.codePointAt(0))) return;
        // A whole sentence in bold is emphasis, not a name.
        if (cleaned.split("\\s+").length > 7) return;
        if (places.stream().noneMatch(p -> p.equalsIgnoreCase(cleaned))) places.add(cleaned);
    }

    private static void addCitation(List<ParsedAnswer.Citation> out, String domain, String title, String url) {
        String d = domain;
        if ((d == null || d.isBlank()) && url != null) d = host(url);
        if (d == null || d.isBlank()) return;
        d = d.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "").replaceFirst("^m\\.", "");
        String key = d + "|" + (title == null ? "" : title);
        for (ParsedAnswer.Citation c : out) if ((c.domain() + "|" + (c.title() == null ? "" : c.title())).equals(key)) return;
        out.add(new ParsedAnswer.Citation(d, title, url));
    }

    private static List<String> subQueries(JsonNode r) {
        List<String> out = new ArrayList<>();
        for (JsonNode q : r.path("fan_out_queries")) if (q.isTextual()) out.add(q.asText());
        return cap(out, MAX_SUB_QUERIES);
    }

    static String host(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            String h = URI.create(url.trim()).getHost();
            return h == null ? null : h.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String clean(String s) {
        return IMAGE.matcher(s).replaceAll("").replaceAll("\n{3,}", "\n\n").trim();
    }

    private static <T> List<T> cap(List<T> list, int max) {
        return list.size() <= max ? list : new ArrayList<>(list.subList(0, max));
    }

    /** For the history JSON column. */
    static List<Map<String, String>> citationMaps(List<ParsedAnswer.Citation> citations) {
        List<Map<String, String>> out = new ArrayList<>();
        for (ParsedAnswer.Citation c : citations) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("domain", c.domain());
            if (c.title() != null) m.put("title", c.title());
            if (c.url() != null) m.put("url", c.url());
            out.add(m);
        }
        return out;
    }
}
