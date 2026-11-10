package com.salonreview.landing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.salonreview.sms.ConsultationOfferService;

/** Read side of the PMU booking site: technique catalog, live procedure menu, availability, offer check. */
@Service
public class PmuLandingService {

    private static final Logger log = LoggerFactory.getLogger(PmuLandingService.class);
    private static final double DEPOSIT_THRESHOLD = 300.0;
    private static final double DEPOSIT_AMOUNT = 100.0;
    private static final String NAILS_URL = "https://akluxnails.com/";
    private static final Set<String> EXCLUDED_CATEGORIES = Set.of("DEPOSIT", "WAX");
    private static final List<String> EXCLUDED_NAME_PARTS = List.of("consultation", "deposit", "model");

    private record CategoryRule(String category, String section, String group) {
    }

    private static final List<CategoryRule> RULES = List.of(
            new CategoryRule("COLOR BOOSTER", "clients", "Color booster"),
            new CategoryRule("TOUCH UP", "clients", "Touch-up"),
            new CategoryRule("EYEBROWS", "pmu", "Brows"),
            new CategoryRule("LIPS", "pmu", "Lips"),
            new CategoryRule("EYELINER", "pmu", "Eyes"),
            new CategoryRule("PARAMEDICAL TATTOOING", "paramedical", "Paramedical tattooing"),
            new CategoryRule("BROWS & LASHES", "lashes", "Brows & lashes"));

    private record SectionDef(String key, String title, String subtitle) {
    }

    private static final List<SectionDef> SECTIONS = List.of(
            new SectionDef("pmu", "Permanent makeup", "Brows, lips and eyes"),
            new SectionDef("paramedical", "Paramedical", "Scar, stretch mark and vitiligo camouflage, areola, scalp"),
            new SectionDef("lashes", "Brows & lashes", "Lamination, tint and shaping"),
            new SectionDef("clients", "For our clients", "Touch-ups and color boosters"));

    private static final class Option {
        String variationId;
        long variationVersion;
        String label;
        List<String> teamMemberIds;
        List<String> artistNames;
        double price;
        int durationMinutes;
        double depositAmount;

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("variation_id", variationId);
            m.put("variation_version", variationVersion);
            m.put("label", label);
            m.put("team_member_ids", teamMemberIds);
            m.put("artist_names", artistNames);
            m.put("price", price);
            m.put("duration_minutes", durationMinutes);
            m.put("deposit_amount", depositAmount);
            return m;
        }
    }

    private static final class MenuService {
        String id;
        String name;
        String description;
        List<Option> options;
        double priceFrom() {
            return options.get(0).price;
        }

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("description", description);
            m.put("options", options.stream().map(Option::toJson).toList());
            m.put("artists", artistChoices(options));
            m.put("price_from", priceFrom());
            return m;
        }
    }

    private record Placed(String section, String group, MenuService service) {
    }

    private final LandingSquareGateway square;
    private final ConsultationOfferService offers;
    private final Clock clock;

    @Autowired
    public PmuLandingService(LandingSquareGateway square, ConsultationOfferService offers) {
        this(square, offers, Clock.systemUTC());
    }

    PmuLandingService(LandingSquareGateway square, ConsultationOfferService offers, Clock clock) {
        this.square = square;
        this.offers = offers;
        this.clock = clock;
    }

    // ---- catalog ----

    public Map<String, Object> catalog(long businessId) {
        Map<String, JsonNode> items = square.items(businessId);
        List<Map<String, Object>> techniques = new ArrayList<>();
        for (LandingDefinitions.Technique t : LandingDefinitions.TECHNIQUES) {
            JsonNode v = variation(items, t.itemId(), t.variationId());
            JsonNode vd = v.path("item_variation_data");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("slug", t.slug());
            m.put("name", t.name());
            m.put("description", t.description());
            m.put("price", vd.path("price_money").path("amount").asLong(0) / 100.0);
            m.put("duration_minutes", minutes(vd));
            m.put("variation_id", v.path("id").asText());
            m.put("variation_version", v.path("version").asLong());
            m.put("public", t.isPublic());
            techniques.add(m);
        }
        List<Map<String, Object>> consultations = new ArrayList<>();
        for (LandingDefinitions.Consultation c : LandingDefinitions.CONSULTATIONS) {
            JsonNode v = variation(items, c.itemId(), c.variationId());
            JsonNode vd = v.path("item_variation_data");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("slug", c.slug());
            m.put("name", c.name());
            m.put("description", c.description());
            m.put("price", vd.path("price_money").path("amount").asLong(0) / 100.0);
            m.put("duration_minutes", minutes(vd));
            m.put("variation_id", v.path("id").asText());
            m.put("variation_version", v.path("version").asLong());
            m.put("team_member_ids", c.teamMemberIds());
            consultations.add(m);
        }
        JsonNode deposit = variation(items, LandingDefinitions.DEPOSIT_ITEM_ID, LandingDefinitions.DEPOSIT_VARIATION_ID);
        LandingSquareGateway.Credentials creds = square.credentials(businessId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("techniques", techniques);
        out.put("consultations", consultations);
        out.put("deposit_amount", deposit.path("item_variation_data").path("price_money").path("amount").asLong(0) / 100.0);
        out.put("square_application_id", creds.applicationId());
        out.put("square_location_id", creds.locationId());
        return out;
    }

    private static JsonNode variation(Map<String, JsonNode> items, String itemId, String variationId) {
        JsonNode item = items.get(itemId);
        if (item == null) {
            throw new LandingException(502,
                    "Square catalog item '" + itemId + "' was not found. It may have been deleted or archived.");
        }
        for (JsonNode v : item.path("item_data").path("variations")) {
            if (variationId.equals(v.path("id").asText())) return v;
        }
        throw new LandingException(502, "Variation '" + variationId + "' not found on item '" + itemId + "'");
    }

    private static int minutes(JsonNode vd) {
        return (int) (vd.path("service_duration").asLong(0) / 60_000);
    }

    // ---- menu ----

    public Map<String, Object> menu(long businessId) {
        Map<String, JsonNode> items = square.items(businessId);
        Map<String, String> categories = square.categoryNames(businessId);
        Map<String, JsonNode> team = square.team(businessId);
        LandingSquareGateway.Credentials creds = square.credentials(businessId);
        Function<String, String> artistName = id -> {
            JsonNode m = team.get(id);
            return m == null ? null : NailsLandingService.displayName(m);
        };

        Map<String, List<MenuService>> buckets = new LinkedHashMap<>();
        Map<String, String[]> bucketKeys = new LinkedHashMap<>();
        for (JsonNode item : items.values()) {
            Placed placed = place(item, categories, artistName);
            if (placed == null) continue;
            String key = placed.section() + "\u0000" + placed.group();
            bucketKeys.putIfAbsent(key, new String[] {placed.section(), placed.group()});
            List<MenuService> bucket = buckets.computeIfAbsent(key, k -> new ArrayList<>());
            MenuService same = bucket.stream()
                    .filter(s -> s.name.equalsIgnoreCase(placed.service().name)).findFirst().orElse(null);
            if (same == null) {
                bucket.add(placed.service());
            } else {
                List<Option> merged = new ArrayList<>(same.options);
                merged.addAll(placed.service().options);
                merged.sort(Comparator.comparingDouble(o -> o.price));
                same.options = merged;
            }
        }

        List<Map<String, Object>> sections = new ArrayList<>();
        for (SectionDef def : SECTIONS) {
            List<Map.Entry<String, List<MenuService>>> groups = new ArrayList<>();
            for (Map.Entry<String, List<MenuService>> e : buckets.entrySet()) {
                if (bucketKeys.get(e.getKey())[0].equals(def.key())) groups.add(e);
            }
            List<String> order = RULES.stream().filter(r -> r.section().equals(def.key())).map(CategoryRule::group).toList();
            groups.sort(Comparator.comparingInt(e -> {
                int i = order.indexOf(bucketKeys.get(e.getKey())[1]);
                return i >= 0 ? i : order.size();
            }));
            List<Map<String, Object>> groupJson = new ArrayList<>();
            for (Map.Entry<String, List<MenuService>> e : groups) {
                String title = bucketKeys.get(e.getKey())[1];
                List<MenuService> services = new ArrayList<>(e.getValue());
                services.sort(Comparator.comparingDouble(MenuService::priceFrom).thenComparing(s -> s.name));
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("key", def.key() + "-" + title.toLowerCase().replace(" ", "-").replace("&", "and"));
                g.put("title", title);
                g.put("services", services.stream().map(MenuService::toJson).toList());
                groupJson.add(g);
            }
            if (!groupJson.isEmpty()) {
                sections.add(section(def.key(), def.title(), def.subtitle(), groupJson, null, false));
            }
        }
        sections.add(section("makeup", "Makeup", "Wedding and event makeup: message us", List.of(), null, true));
        sections.add(section("nails", "Nails", "Manicure and pedicure at AK.LUX.NAILS", List.of(), NAILS_URL, false));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sections", sections);
        out.put("square_application_id", creds.applicationId());
        out.put("square_location_id", creds.locationId());
        return out;
    }

    private static Map<String, Object> section(String key, String title, String subtitle, List<Map<String, Object>> groups,
                                               String externalUrl, boolean contactOnly) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("title", title);
        m.put("subtitle", subtitle);
        m.put("groups", groups);
        m.put("external_url", externalUrl);
        m.put("contact_only", contactOnly);
        return m;
    }

    private static double depositFor(double price) {
        return price >= DEPOSIT_THRESHOLD ? DEPOSIT_AMOUNT : 0.0;
    }

    private Placed place(JsonNode item, Map<String, String> categories, Function<String, String> artistName) {
        if (item.path("is_deleted").asBoolean(false)) return null;
        JsonNode data = item.path("item_data");
        if (data.isMissingNode() || !"APPOINTMENTS_SERVICE".equals(data.path("product_type").asText(""))) return null;
        String name = data.path("name").asText("").strip();
        if (name.isEmpty()) return null;
        String lower = name.toLowerCase();
        if (EXCLUDED_NAME_PARTS.stream().anyMatch(lower::contains)) return null;

        List<String> categoryIds = new ArrayList<>();
        for (JsonNode c : data.path("categories")) {
            if (c.hasNonNull("id")) categoryIds.add(c.path("id").asText());
        }
        if (data.hasNonNull("category_id")) categoryIds.add(data.path("category_id").asText());
        List<String> categoryNames = categoryIds.stream()
                .map(id -> categories.getOrDefault(id, "").toUpperCase()).toList();
        if (categoryNames.stream().anyMatch(EXCLUDED_CATEGORIES::contains)) return null;
        CategoryRule rule = RULES.stream().filter(r -> categoryNames.contains(r.category())).findFirst().orElse(null);
        if (rule == null) return null;

        List<Option> options = new ArrayList<>();
        for (JsonNode v : data.path("variations")) {
            JsonNode vd = v.path("item_variation_data");
            if (vd.isMissingNode() || !vd.path("available_for_booking").asBoolean(false)) continue;
            List<String> team = new ArrayList<>();
            for (JsonNode t : vd.path("team_member_ids")) {
                String name2 = artistName.apply(t.asText());
                if (name2 != null && !name2.isEmpty()) team.add(t.asText());
            }
            if (team.isEmpty()) continue;
            double price = vd.path("price_money").path("amount").asLong(0) / 100.0;
            String label = vd.path("name").asText("").strip();
            if (label.isEmpty() || List.of("regular", "standard", "default").contains(label.toLowerCase())) label = null;
            Option o = new Option();
            o.variationId = v.path("id").asText();
            o.variationVersion = v.path("version").asLong();
            o.label = label;
            o.teamMemberIds = team;
            o.artistNames = team.stream().map(artistName).toList();
            o.price = price;
            o.durationMinutes = minutes(vd);
            o.depositAmount = depositFor(price);
            options.add(o);
        }
        if (options.isEmpty()) return null;
        options.sort(Comparator.comparingDouble(o -> o.price));
        String description = data.path("description").asText("").strip();
        MenuService s = new MenuService();
        s.id = item.path("id").asText();
        s.name = name;
        s.description = description.isEmpty() ? null : description;
        s.options = options;
        return new Placed(rule.section(), rule.group(), s);
    }

    private static List<Map<String, Object>> artistChoices(List<Option> options) {
        Map<String, String> artists = new LinkedHashMap<>();
        for (Option o : options) {
            for (int i = 0; i < o.teamMemberIds.size(); i++) {
                artists.putIfAbsent(o.teamMemberIds.get(i), o.artistNames.get(i));
            }
        }
        List<Map<String, Object>> choices = new ArrayList<>();
        List<Double> prices = new ArrayList<>();
        artists.forEach((tm, name) -> {
            List<Option> mine = options.stream().filter(o -> o.teamMemberIds.contains(tm)).toList();
            String first = name.split("\\s+")[0].toLowerCase();
            first = first.substring(0, Math.min(5, first.length()));
            final String prefix = first;
            List<Option> named = mine.stream()
                    .filter(o -> o.label != null && o.label.toLowerCase().replace(" ", "").startsWith(prefix)).toList();
            Option o = (named.isEmpty() ? mine : named).get(0);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("team_member_id", tm);
            m.put("artist_name", name);
            m.put("variation_id", o.variationId);
            m.put("price", o.price);
            m.put("duration_minutes", o.durationMinutes);
            m.put("deposit_amount", o.depositAmount);
            choices.add(m);
        });
        choices.sort(Comparator.comparingDouble(m -> (Double) m.get("price")));
        return choices;
    }

    // ---- availability ----

    public Map<String, Object> techniqueAvailability(long businessId, String slug, int days) {
        LandingDefinitions.Technique t = LandingDefinitions.TECHNIQUES.stream()
                .filter(x -> x.slug().equals(slug)).findFirst()
                .orElseThrow(() -> new LandingException(400, "Unknown technique '" + slug + "'"));
        return search(businessId, t.variationId(), t.teamMemberIds(), days);
    }

    public Map<String, Object> consultationAvailability(long businessId, String slug, int days) {
        LandingDefinitions.Consultation c = LandingDefinitions.CONSULTATIONS.stream()
                .filter(x -> x.slug().equals(slug)).findFirst()
                .orElseThrow(() -> new LandingException(400, "Unknown consultation '" + slug + "'"));
        return search(businessId, c.variationId(), c.teamMemberIds(), days);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> serviceAvailability(long businessId, String variationId, int days) {
        Map<String, Object> menu = menu(businessId);
        for (Map<String, Object> section : (List<Map<String, Object>>) menu.get("sections")) {
            for (Map<String, Object> group : (List<Map<String, Object>>) section.get("groups")) {
                for (Map<String, Object> service : (List<Map<String, Object>>) group.get("services")) {
                    for (Map<String, Object> option : (List<Map<String, Object>>) service.get("options")) {
                        if (variationId.equals(option.get("variation_id"))) {
                            return search(businessId, variationId, (List<String>) option.get("team_member_ids"), days);
                        }
                    }
                }
            }
        }
        throw new LandingException(404, "Unknown service option '" + variationId + "'");
    }

    private Map<String, Object> search(long businessId, String variationId, List<String> teamMemberIds, int days) {
        Instant now = clock.instant();
        String startAt = now.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
        String endAt = now.plus(Duration.ofDays(days)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
        List<JsonNode> found = square.availability(businessId, List.of(variationId), teamMemberIds, startAt, endAt);
        Map<String, JsonNode> team = square.team(businessId);
        List<Map<String, Object>> slots = new ArrayList<>();
        for (JsonNode a : found) {
            JsonNode segment = a.path("appointment_segments").path(0);
            String teamMemberId = segment.path("team_member_id").asText();
            String start = a.path("start_at").asText();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("start_at", start);
            m.put("end_at", NailsLandingService.plusMinutes(start, segment.path("duration_minutes").asInt(0)));
            m.put("team_member_id", teamMemberId);
            JsonNode member = team.get(teamMemberId);
            m.put("artist_name", member == null ? null : NailsLandingService.displayName(member));
            slots.add(m);
        }
        slots.sort(Comparator.comparing(s -> (String) s.get("start_at")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("slots", slots);
        return out;
    }

    // ---- personal offer ----

    public Map<String, Object> offer(long businessId, String token) {
        ConsultationOfferService.Offer offer;
        try {
            offer = offers.check(businessId, token.length() > 200 ? token.substring(0, 200) : token);
        } catch (RuntimeException e) {
            log.warn("Consultation offer check failed for business {}", businessId, e);
            offer = null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        boolean valid = offer != null && offer.valid();
        m.put("valid", valid);
        m.put("discount_amount", valid ? offer.discountCents() / 100.0 : 0.0);
        m.put("min_spend", valid ? offer.minSpendCents() / 100.0 : 0.0);
        m.put("expires_text", valid ? offer.expiresText() : null);
        m.put("first_name", valid ? offer.firstName() : null);
        return m;
    }
}
