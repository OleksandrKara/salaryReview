package com.salonreview.aivisibility;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Detects the fixed list of page components from design.md D10, each with a short evidence string
 * so every ✓ can be checked by eye. Deterministic heuristics; a component counts toward a gap only
 * when at least 2 competitor pages have it, so one misread page cannot create a gap alone. */
public final class PageComponentExtractor {

    public enum Component {
        BEFORE_AFTER("Before/after photos"), FAQ("FAQ"), PRICES("Prices on the page"), REVIEWS_ON_PAGE("Reviews on the page"),
        RATING_STATED("Rating stated"), ARTIST_BIO("Artist bio / experience"), PROCESS_STEPS("Process / what to expect"),
        AFTERCARE("Aftercare / healing"), VIDEO("Video"), BOOKING_CTA("Booking button"), FINANCING("Financing / payment plans"),
        MAP_NAP("Map, address and phone"), BUSINESS_SCHEMA("Business schema markup"), PRESS("Press / as featured in"),
        GUARANTEE("Guarantee / touch-up policy"), UPDATED_DATE("Updated date");

        public final String label;

        Component(String label) {
            this.label = label;
        }
    }

    public record Found(boolean present, String evidence) {
    }

    public record Result(Map<Component, Found> components, Map<String, Object> metrics, boolean businessPage) {
    }

    private static final Pattern PRICE = Pattern.compile("\\$\\s?\\d{2,4}(?:[.,]\\d{2})?");
    private static final Pattern RATING = Pattern.compile("\\b[45][.,]\\d\\s*(?:stars?|★|/\\s?5|out of 5|\\(\\d{2,5}\\)|rating)|\\b\\d{2,5}\\+?\\s+(?:google\\s+|5[- ]star\\s+)?reviews\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern YEARS = Pattern.compile("\\b\\d{1,2}\\+?\\s+years?\\s+(?:of\\s+)?(?:experience|in the industry|in business|practicing)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ADDRESS = Pattern.compile("\\b\\d{2,5}\\s+[A-Z0-9][\\w.]*(?:\\s+[A-Z0-9][\\w.]*){0,3}\\s+(?:St|Street|Ave|Avenue|Blvd|Boulevard|Rd|Road|Dr|Drive|Way|Ln|Lane|Pkwy|Hwy|Ct|Pl)\\b");
    private static final Pattern PHONE = Pattern.compile("\\(?\\b\\d{3}\\)?[\\s.-]\\d{3}[\\s.-]\\d{4}\\b");
    private static final Pattern DATE_NEAR_UPDATED = Pattern.compile("(?:updated|last modified|modified)\\s*:?\\s*(?:on\\s+)?(?:[A-Z][a-z]{2,8}\\.?\\s+\\d{1,2},?\\s+\\d{4}|\\d{1,2}[/.]\\d{1,2}[/.]\\d{2,4}|\\d{4}-\\d{2}-\\d{2})", Pattern.CASE_INSENSITIVE);
    private static final Pattern TYPE = Pattern.compile("\"@type\"\\s*:\\s*(\\[[^\\]]*]|\"[^\"]+\")");
    private static final Set<String> BUSINESS_TYPES = Set.of("localbusiness", "beautysalon", "healthandbeautybusiness", "nailsalon",
            "dayspa", "medicalbusiness", "medicalclinic", "hairsalon", "professionalservice", "store", "organization");

    private PageComponentExtractor() {
    }

    public static Result extract(PageSnapshot p) {
        Map<Component, Found> out = new LinkedHashMap<>();
        String text = p.text();
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> headingTexts = p.headings().stream().map(h -> h.contains("|") ? h.substring(h.indexOf('|') + 1) : h).toList();
        String ld = String.join("\n", p.jsonLd());
        String ldLower = ld.toLowerCase(Locale.ROOT);
        Set<String> types = schemaTypes(ld);

        // BEFORE_AFTER
        long baImages = p.images().stream().map(s -> s.toLowerCase(Locale.ROOT)).filter(s -> s.contains("before") || s.contains("after") || s.contains("b&a") || s.contains("healed")).count();
        String baHeading = first(headingTexts, h -> h.toLowerCase(Locale.ROOT).matches(".*before\\s*(?:&|and|/|\\+)\\s*after.*|.*results gallery.*|.*healed results.*"));
        out.put(Component.BEFORE_AFTER, baImages >= 2 ? new Found(true, baImages + " before/after images")
                : baHeading != null ? new Found(true, "Heading: " + baHeading) : absent());

        // FAQ
        long questionHeadings = headingTexts.stream().filter(h -> h.trim().endsWith("?")).count();
        String faqHeading = first(headingTexts, h -> h.toLowerCase(Locale.ROOT).matches(".*\\b(faq|faqs|frequently asked|questions)\\b.*"));
        long questionLines = lower.lines().filter(l -> l.trim().endsWith("?") && l.trim().length() > 12).count();
        if (types.contains("faqpage")) out.put(Component.FAQ, new Found(true, "FAQPage schema"));
        else if (faqHeading != null && (questionHeadings >= 2 || questionLines >= 3)) out.put(Component.FAQ, new Found(true, "Heading: " + faqHeading));
        else if (questionHeadings >= 3) out.put(Component.FAQ, new Found(true, questionHeadings + " question headings"));
        else out.put(Component.FAQ, absent());

        // PRICES
        Matcher pm = PRICE.matcher(text);
        int prices = 0;
        String firstPrice = null;
        while (pm.find()) { prices++; if (firstPrice == null) firstPrice = pm.group(); }
        out.put(Component.PRICES, prices >= 2 ? new Found(true, prices + " prices, e.g. " + firstPrice)
                : ldLower.contains("\"price\"") ? new Found(true, "Price in schema") : absent());

        // REVIEWS_ON_PAGE
        String reviewHeading = first(headingTexts, h -> h.toLowerCase(Locale.ROOT).matches(".*\\b(reviews?|testimonials?|what (our )?(clients|customers) say|client love|kind words)\\b.*"));
        out.put(Component.REVIEWS_ON_PAGE, types.contains("review") || types.contains("aggregaterating") ? new Found(true, "Review schema")
                : reviewHeading != null ? new Found(true, "Heading: " + reviewHeading) : absent());

        // RATING_STATED
        Matcher rm = RATING.matcher(text);
        out.put(Component.RATING_STATED, rm.find() ? new Found(true, snippet(text, rm.start(), rm.end()))
                : ldLower.contains("ratingvalue") ? new Found(true, "ratingValue in schema") : absent());

        // ARTIST_BIO
        Matcher ym = YEARS.matcher(text);
        String bioHeading = first(headingTexts, h -> h.toLowerCase(Locale.ROOT).matches(".*\\b(about( me| us)?|meet (the |your |our )?(artist|founder|team|technician)|our (artists|team)|founder)\\b.*"));
        out.put(Component.ARTIST_BIO, types.contains("person") ? new Found(true, "Person schema")
                : ym.find() ? new Found(true, snippet(text, ym.start(), ym.end()))
                : bioHeading != null ? new Found(true, "Heading: " + bioHeading) : absent());

        // PROCESS_STEPS
        String processHeading = first(headingTexts, h -> h.toLowerCase(Locale.ROOT).matches(".*\\b(process|how it works|what to expect|step\\s*1|the procedure|before your appointment)\\b.*"));
        out.put(Component.PROCESS_STEPS, processHeading != null ? new Found(true, "Heading: " + processHeading) : absent());

        // AFTERCARE
        String careHeading = first(headingTexts, h -> h.toLowerCase(Locale.ROOT).matches(".*\\b(aftercare|after care|healing|after the procedure|after your (procedure|session|appointment))\\b.*"));
        out.put(Component.AFTERCARE, careHeading != null ? new Found(true, "Heading: " + careHeading)
                : lower.contains("aftercare") ? new Found(true, "Mentions aftercare") : absent());

        // VIDEO
        String video = p.media().stream().filter(m -> m.matches("(?i).*(youtube|youtu\\.be|vimeo|instagram\\.com/(p|reel)|tiktok|\\.mp4|\\.webm|\\.m3u8).*")).findFirst().orElse(null);
        out.put(Component.VIDEO, video != null ? new Found(true, shortUrl(video)) : absent());

        // BOOKING_CTA
        String booking = p.links().stream().filter(l -> l.toLowerCase(Locale.ROOT).matches(".*\\b(book|booking|schedule|appointment|consultation|reserve)\\b.*")).findFirst().orElse(null);
        out.put(Component.BOOKING_CTA, booking != null ? new Found(true, booking.split("\\|", 2)[0].isBlank() ? shortUrl(booking) : booking.split("\\|", 2)[0]) : absent());

        // FINANCING
        Matcher fm = Pattern.compile("\\b(financing|payment plans?|pay over time|cherry|afterpay|klarna|affirm|sezzle)\\b", Pattern.CASE_INSENSITIVE).matcher(text);
        out.put(Component.FINANCING, fm.find() ? new Found(true, snippet(text, fm.start(), fm.end())) : absent());

        // MAP_NAP
        String map = p.media().stream().filter(m -> m.contains("google.com/maps") || m.contains("maps.google")).findFirst().orElse(null);
        Matcher am = ADDRESS.matcher(text);
        boolean address = am.find();
        boolean phone = PHONE.matcher(text).find();
        out.put(Component.MAP_NAP, map != null ? new Found(true, "Google Maps embed")
                : address && phone ? new Found(true, "Address: " + snippet(text, am.start(), am.end())) : absent());

        // BUSINESS_SCHEMA
        String bizType = types.stream().filter(BUSINESS_TYPES::contains).findFirst().orElse(null);
        out.put(Component.BUSINESS_SCHEMA, bizType != null ? new Found(true, "@type " + bizType) : absent());

        // PRESS
        Matcher prm = Pattern.compile("\\b(as (seen|featured) (on|in)|featured in|in the press|press coverage)\\b", Pattern.CASE_INSENSITIVE).matcher(text);
        out.put(Component.PRESS, prm.find() ? new Found(true, snippet(text, prm.start(), prm.end())) : absent());

        // GUARANTEE
        Matcher gm = Pattern.compile("\\b(guarantee[d]?|touch-?ups? (is |are )?included|free touch-?up|complimentary touch-?up|free correction)\\b", Pattern.CASE_INSENSITIVE).matcher(text);
        out.put(Component.GUARANTEE, gm.find() ? new Found(true, snippet(text, gm.start(), gm.end())) : absent());

        // UPDATED_DATE
        Matcher dm = DATE_NEAR_UPDATED.matcher(text);
        out.put(Component.UPDATED_DATE, ldLower.contains("datemodified") ? new Found(true, "dateModified in schema")
                : dm.find() ? new Found(true, snippet(text, dm.start(), dm.end())) : absent());

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("words", p.wordCount());
        metrics.put("h2", p.headings().stream().filter(h -> h.startsWith("H2|")).count());
        metrics.put("images", p.images().size());
        metrics.put("schemaTypes", new ArrayList<>(types));
        metrics.put("h2Texts", p.headings().stream().filter(h -> h.startsWith("H2|")).map(h -> h.substring(3)).limit(20).toList());

        boolean businessPage = bizType != null || out.get(Component.BOOKING_CTA).present() || out.get(Component.MAP_NAP).present();
        return new Result(out, metrics, businessPage);
    }

    static Set<String> schemaTypes(String ld) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = TYPE.matcher(ld);
        while (m.find()) {
            for (String t : m.group(1).replaceAll("[\\[\\]\"]", "").split(",")) {
                String v = t.trim().toLowerCase(Locale.ROOT);
                if (!v.isEmpty()) out.add(v);
            }
        }
        return out;
    }

    private static Found absent() {
        return new Found(false, null);
    }

    private static String first(List<String> items, java.util.function.Predicate<String> p) {
        return items.stream().filter(p).findFirst().map(s -> s.length() > 80 ? s.substring(0, 80) : s).orElse(null);
    }

    private static String snippet(String text, int start, int end) {
        int s = Math.max(0, start - 20), e = Math.min(text.length(), end + 20);
        return text.substring(s, e).replaceAll("\\s+", " ").trim();
    }

    private static String shortUrl(String s) {
        String v = s.contains("|") ? s.substring(s.indexOf('|') + 1) : s;
        return v.length() > 60 ? v.substring(0, 60) + "…" : v;
    }
}
