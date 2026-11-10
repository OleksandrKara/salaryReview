package com.salonreview.landing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;

/** Read side of the nails booking site: cart menu, artists and live availability. */
@Service
public class NailsLandingService {

    static final String ANY_ARTIST = "any";
    private static final ZoneId SALON_TZ = ZoneId.of("America/Los_Angeles");
    private static final int SHIFT_START_HOUR = 8;
    private static final int SHIFT_END_HOUR = 20;
    private static final Duration CONFIRMATION_GAP = Duration.ofHours(2);

    record TierPricing(String tier, String variationId, long variationVersion, double price, Double compareAtPrice,
                       int durationMinutes, List<String> teamMemberIds) {
        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tier", tier);
            m.put("variation_id", variationId);
            m.put("variation_version", variationVersion);
            m.put("price", price);
            m.put("compare_at_price", compareAtPrice);
            m.put("duration_minutes", durationMinutes);
            m.put("team_member_ids", teamMemberIds);
            return m;
        }
    }

    record Offer(String slug, String name, String offerLabel, String description, boolean firstTime,
                 List<TierPricing> pricing) {
        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("slug", slug);
            m.put("name", name);
            m.put("offer_label", offerLabel);
            m.put("description", description);
            m.put("is_first_time_offer", firstTime);
            m.put("pricing", pricing.stream().map(TierPricing::toJson).toList());
            return m;
        }
    }

    record Addon(String slug, String name, String description, double price, int durationMinutes, String variationId,
                 long variationVersion, List<String> teamMemberIds, String requiresSlug) {
        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("slug", slug);
            m.put("name", name);
            m.put("description", description);
            m.put("price", price);
            m.put("duration_minutes", durationMinutes);
            m.put("variation_id", variationId);
            m.put("variation_version", variationVersion);
            m.put("team_member_ids", teamMemberIds);
            m.put("requires_slug", requiresSlug);
            return m;
        }
    }

    record CartMenu(Offer manicure, Offer pedicure, Addon design, String fourHandPriceDescription) {
        Map<String, Object> toJson() {
            LandingDefinitions.FourHand fh = LandingDefinitions.FOUR_HAND;
            Map<String, Object> four = new LinkedHashMap<>();
            four.put("slug", fh.slug());
            four.put("name", fh.name());
            four.put("description", fh.description());
            four.put("price_description", fourHandPriceDescription);
            four.put("price", fh.displayPrice());
            four.put("compare_at_price", null);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("manicure", manicure.toJson());
            m.put("pedicure", pedicure.toJson());
            m.put("design_addon", design.toJson());
            m.put("four_hand_request", four);
            return m;
        }
    }

    private record Segment(String slug, String name, String variationId, long variationVersion, double price,
                           Double compareAtPrice, int durationMinutes) {
        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("service_slug", slug);
            m.put("name", name);
            m.put("variation_id", variationId);
            m.put("variation_version", variationVersion);
            m.put("price", price);
            m.put("compare_at_price", compareAtPrice);
            m.put("duration_minutes", durationMinutes);
            return m;
        }
    }

    private record Combo(String tier, List<Segment> segments, Set<String> eligible) {
        double totalPrice() {
            double sum = 0;
            for (Segment s : segments) sum += s.price();
            return sum;
        }

        double totalCompareAt() {
            double sum = 0;
            for (Segment s : segments) sum += s.compareAtPrice() != null ? s.compareAtPrice() : s.price();
            return sum;
        }

        int totalDuration() {
            return segments.stream().mapToInt(Segment::durationMinutes).sum();
        }
    }

    private static final class Slot {
        final String startAt;
        final Map<String, Object> json = new LinkedHashMap<>();
        final double price;

        Slot(String startAt, double price) {
            this.startAt = startAt;
            this.price = price;
        }
    }

    private final LandingSquareGateway square;
    private final Clock clock;

    public NailsLandingService(LandingSquareGateway square) {
        this(square, Clock.systemUTC());
    }

    NailsLandingService(LandingSquareGateway square, Clock clock) {
        this.square = square;
        this.clock = clock;
    }

    public Map<String, Object> cartMenu(long businessId) {
        return buildCartMenu(businessId, square.items(businessId)).toJson();
    }

    public List<Map<String, Object>> artists(long businessId) {
        CartMenu menu = buildCartMenu(businessId, square.items(businessId));
        return listArtists(menu, square.team(businessId)).stream().map(a -> a.json).toList();
    }

    // ---- catalog ----

    private CartMenu buildCartMenu(long businessId, Map<String, JsonNode> items) {
        Offer manicure = buildOffer(LandingDefinitions.SERVICE_OFFERS.get(0), items);
        Offer pedicure = buildOffer(LandingDefinitions.SERVICE_OFFERS.get(1), items);
        Addon design = buildAddon(LandingDefinitions.DESIGN, items);
        return new CartMenu(manicure, pedicure, design, fourHandPriceDescription(items));
    }

    private static JsonNode item(Map<String, JsonNode> items, String id) {
        JsonNode item = items.get(id);
        if (item == null) {
            throw new LandingException(502,
                    "Square catalog item '" + id + "' was not found. It may have been deleted or archived.");
        }
        return item;
    }

    private static List<String> stringList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) arr.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static int minutes(JsonNode vd) {
        return (int) (vd.path("service_duration").asLong(0) / 60_000);
    }

    private Offer buildOffer(LandingDefinitions.ServiceOffer def, Map<String, JsonNode> items) {
        JsonNode firstTime = item(items, def.firstTimeItemId());
        Map<String, Double> regularByTier = new LinkedHashMap<>();
        if (def.regularPriceItemId() != null) {
            for (JsonNode v : item(items, def.regularPriceItemId()).path("item_data").path("variations")) {
                JsonNode vd = v.path("item_variation_data");
                if (!vd.path("price_money").has("amount")) continue;
                regularByTier.put(tierOf(vd), vd.path("price_money").path("amount").asLong() / 100.0);
            }
        }
        List<TierPricing> pricing = new ArrayList<>();
        for (JsonNode v : firstTime.path("item_data").path("variations")) {
            JsonNode vd = v.path("item_variation_data");
            if (!vd.path("price_money").has("amount")) continue;
            String tier = tierOf(vd);
            pricing.add(new TierPricing(tier, v.path("id").asText(), v.path("version").asLong(),
                    vd.path("price_money").path("amount").asLong() / 100.0, regularByTier.get(tier), minutes(vd),
                    stringList(vd.path("team_member_ids"))));
        }
        return new Offer(def.slug(), def.name(), def.offerLabel(), def.description(), def.firstTimeOffer(), pricing);
    }

    private static String tierOf(JsonNode vd) {
        return LandingDefinitions.isTopTierName(vd.path("name").asText("")) ? "top" : "regular";
    }

    private Addon buildAddon(LandingDefinitions.FlatAddon def, Map<String, JsonNode> items) {
        JsonNode variations = item(items, def.itemId()).path("item_data").path("variations");
        if (!variations.isArray() || variations.isEmpty()) {
            throw new LandingException(400, "Add-on item '" + def.itemId() + "' has no variations");
        }
        JsonNode v = variations.get(0);
        JsonNode vd = v.path("item_variation_data");
        return new Addon(def.slug(), def.name(), def.description(), vd.path("price_money").path("amount").asLong(0) / 100.0,
                minutes(vd), v.path("id").asText(), v.path("version").asLong(), stringList(vd.path("team_member_ids")),
                def.requiresSlug());
    }

    private String fourHandPriceDescription(Map<String, JsonNode> items) {
        for (JsonNode v : item(items, LandingDefinitions.FOUR_HAND.itemId()).path("item_data").path("variations")) {
            if (LandingDefinitions.FOUR_HAND.variationId().equals(v.path("id").asText())) {
                String d = v.path("item_variation_data").path("price_description").asText("");
                return d.isEmpty() ? null : d;
            }
        }
        return null;
    }

    // ---- artists ----

    private record ArtistRow(String id, String displayName, String tier, Map<String, Object> json) {
    }

    private List<ArtistRow> listArtists(CartMenu menu, Map<String, JsonNode> members) {
        Map<String, String> tiers = new LinkedHashMap<>();
        for (Offer offer : List.of(menu.manicure(), menu.pedicure())) {
            for (TierPricing p : offer.pricing()) {
                for (String id : p.teamMemberIds()) {
                    if (!"top".equals(tiers.get(id))) tiers.put(id, p.tier());
                }
            }
        }
        List<ArtistRow> rows = new ArrayList<>();
        tiers.forEach((id, tier) -> {
            JsonNode member = members.get(id);
            if (member == null) return;
            String name = displayName(member);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("display_name", name);
            m.put("tier", tier);
            m.put("bio", null);
            m.put("photo_url", null);
            rows.add(new ArtistRow(id, name, tier, m));
        });
        rows.sort(Comparator.comparing((ArtistRow a) -> !"top".equals(a.tier())).thenComparing(ArtistRow::displayName));
        return rows;
    }

    static String displayName(JsonNode member) {
        String given = member.path("given_name").asText("");
        String family = member.path("family_name").asText("");
        String initial = family.isEmpty() ? "" : " " + family.substring(0, family.offsetByCodePoints(0, 1)) + ".";
        return (given.isEmpty() ? "Artist" : given) + initial;
    }

    // ---- availability ----

    public Map<String, Object> availability(long businessId, List<String> slugs, String artistSelection, int days) {
        if (slugs.size() == 1 && slugs.get(0).equals(LandingDefinitions.FOUR_HAND.slug())) {
            return fourHandAvailability(businessId, days);
        }
        CartMenu menu = buildCartMenu(businessId, square.items(businessId));
        List<Combo> combos = buildCombos(menu, slugs);
        if (combos.isEmpty()) {
            throw new LandingException(404, "No artist can perform every selected service together.");
        }
        Map<String, String> artistNames = new LinkedHashMap<>();
        listArtists(menu, square.team(businessId)).forEach(a -> artistNames.put(a.id(), a.displayName()));
        double advertised = advertisedPrice(combos);
        Instant now = clock.instant();
        String startAt = earliestBookableStart(now).truncatedTo(ChronoUnit.SECONDS).toString();
        String endAt = now.plus(Duration.ofDays(days)).truncatedTo(ChronoUnit.SECONDS).toString();

        Map<String, Slot> bestByStart = new LinkedHashMap<>();
        for (Combo combo : combos) {
            List<String> teamIds = new ArrayList<>(combo.eligible());
            if (!ANY_ARTIST.equals(artistSelection)) {
                if (!combo.eligible().contains(artistSelection)) continue;
                teamIds = List.of(artistSelection);
            }
            List<JsonNode> found = square.availability(businessId,
                    combo.segments().stream().map(Segment::variationId).toList(), teamIds, startAt, endAt);
            for (JsonNode a : found) {
                String start = a.path("start_at").asText();
                String teamMemberId = a.path("appointment_segments").path(0).path("team_member_id").asText();
                Slot slot = buildSlot(combo, start, teamMemberId, advertised, artistNames);
                Slot current = bestByStart.get(start);
                if (current == null || slot.price < current.price) bestByStart.put(start, slot);
            }
        }
        List<Slot> slots = new ArrayList<>(bestByStart.values());
        if (!slots.isEmpty()) {
            double min = slots.stream().mapToDouble(s -> s.price).min().getAsDouble();
            slots.forEach(s -> s.json.put("is_best_price", s.price == min));
        }
        slots.sort(Comparator.comparing(s -> s.startAt));
        return response(slugs, artistSelection, slots.stream().map(s -> s.json).toList());
    }

    private static Map<String, Object> response(List<String> slugs, String artist, List<Map<String, Object>> slots) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("services", slugs);
        m.put("artist_selection", artist);
        m.put("slots", slots);
        return m;
    }

    private Map<String, Object> fourHandAvailability(long businessId, int days) {
        LandingDefinitions.FourHand fh = LandingDefinitions.FOUR_HAND;
        JsonNode variation = null;
        for (JsonNode v : item(square.items(businessId), fh.itemId()).path("item_data").path("variations")) {
            if (fh.variationId().equals(v.path("id").asText())) variation = v;
        }
        if (variation == null) {
            throw new LandingException(400, "Variation '" + fh.variationId() + "' not found");
        }
        JsonNode vd = variation.path("item_variation_data");
        List<String> team = stringList(vd.path("team_member_ids"));
        if (team.isEmpty()) {
            throw new LandingException(400, "4-hand request variation has no assigned team member");
        }
        int duration = minutes(vd);
        long version = variation.path("version").asLong();
        Instant now = clock.instant();
        String startAt = earliestBookableStart(now).truncatedTo(ChronoUnit.SECONDS).toString();
        String endAt = now.plus(Duration.ofDays(days)).truncatedTo(ChronoUnit.SECONDS).toString();
        List<JsonNode> found = square.availability(businessId, List.of(fh.variationId()), List.of(team.get(0)), startAt, endAt);
        List<Map<String, Object>> slots = new ArrayList<>();
        for (JsonNode a : found) {
            String start = a.path("start_at").asText();
            Segment seg = new Segment(fh.slug(), fh.name(), fh.variationId(), version, 0.0, null, duration);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("start_at", start);
            m.put("end_at", plusMinutes(start, duration));
            m.put("duration_minutes", duration);
            m.put("team_member_id", team.get(0));
            m.put("artist_name", null);
            m.put("tier", "regular");
            m.put("price", 0.0);
            m.put("compare_at_price", null);
            m.put("advertised_price", 0.0);
            m.put("savings", 0.0);
            m.put("is_best_price", true);
            m.put("segments", List.of(seg.toJson()));
            slots.add(m);
        }
        slots.sort(Comparator.comparing(s -> (String) s.get("start_at")));
        return response(List.of(fh.slug()), ANY_ARTIST, slots);
    }

    private List<Combo> buildCombos(CartMenu menu, List<String> slugs) {
        Map<String, Offer> tiered = new LinkedHashMap<>();
        tiered.put(menu.manicure().slug(), menu.manicure());
        tiered.put(menu.pedicure().slug(), menu.pedicure());
        Addon design = menu.design();

        List<String> tieredSlugs = slugs.stream().filter(tiered::containsKey).toList();
        List<String> flatSlugs = slugs.stream().filter(s -> s.equals(design.slug())).toList();
        List<String> unknown = slugs.stream().filter(s -> !tiered.containsKey(s) && !s.equals(design.slug())).toList();
        if (!unknown.isEmpty()) {
            throw new LandingException(400, "Unknown service(s): " + String.join(", ", unknown));
        }
        if (tieredSlugs.isEmpty() && flatSlugs.isEmpty()) {
            throw new LandingException(400, "Select at least one service.");
        }
        if (!flatSlugs.isEmpty() && !tieredSlugs.contains(design.requiresSlug())) {
            throw new LandingException(400,
                    "'" + design.name() + "' requires '" + design.requiresSlug() + "' to also be selected.");
        }

        List<Combo> combos = new ArrayList<>();
        for (String tier : List.of("top", "regular")) {
            List<List<TierPricing>> perService = new ArrayList<>();
            boolean feasible = true;
            for (String slug : tieredSlugs) {
                List<TierPricing> options = tiered.get(slug).pricing().stream().filter(p -> p.tier().equals(tier)).toList();
                if (options.isEmpty()) {
                    feasible = false;
                    break;
                }
                perService.add(options);
            }
            if (!feasible) continue;
            for (List<TierPricing> combination : cartesian(perService)) {
                List<Segment> segments = new ArrayList<>();
                Set<String> eligible = null;
                for (int i = 0; i < tieredSlugs.size(); i++) {
                    Offer offer = tiered.get(tieredSlugs.get(i));
                    TierPricing tp = combination.get(i);
                    Set<String> ids = new LinkedHashSet<>(tp.teamMemberIds());
                    eligible = intersect(eligible, ids);
                    segments.add(new Segment(offer.slug(), offer.name(), tp.variationId(), tp.variationVersion(),
                            tp.price(), tp.compareAtPrice(), tp.durationMinutes()));
                }
                for (int i = 0; i < flatSlugs.size(); i++) {
                    eligible = intersect(eligible, new LinkedHashSet<>(design.teamMemberIds()));
                    segments.add(new Segment(design.slug(), design.name(), design.variationId(), design.variationVersion(),
                            design.price(), null, design.durationMinutes()));
                }
                if (eligible != null && !eligible.isEmpty()) combos.add(new Combo(tier, segments, eligible));
            }
        }
        return combos;
    }

    private static Set<String> intersect(Set<String> current, Set<String> other) {
        if (current == null) return other;
        Set<String> out = new LinkedHashSet<>(current);
        out.retainAll(other);
        return out;
    }

    private static <T> List<List<T>> cartesian(List<List<T>> lists) {
        List<List<T>> result = new ArrayList<>();
        result.add(new ArrayList<>());
        for (List<T> options : lists) {
            List<List<T>> next = new ArrayList<>();
            for (List<T> prefix : result) {
                for (T o : options) {
                    List<T> extended = new ArrayList<>(prefix);
                    extended.add(o);
                    next.add(extended);
                }
            }
            result = next;
        }
        return result;
    }

    private static double advertisedPrice(List<Combo> combos) {
        return combos.stream().filter(c -> c.tier().equals("top")).findFirst().map(Combo::totalPrice)
                .orElseGet(() -> combos.stream().mapToDouble(Combo::totalPrice).min().getAsDouble());
    }

    private static Slot buildSlot(Combo combo, String startAt, String teamMemberId, double advertised,
                                  Map<String, String> artistNames) {
        int duration = combo.totalDuration();
        double price = combo.totalPrice();
        Slot slot = new Slot(startAt, price);
        Map<String, Object> m = slot.json;
        m.put("start_at", startAt);
        m.put("end_at", plusMinutes(startAt, duration));
        m.put("duration_minutes", duration);
        m.put("team_member_id", teamMemberId);
        m.put("artist_name", artistNames.get(teamMemberId));
        m.put("tier", combo.tier());
        m.put("price", price);
        m.put("compare_at_price", combo.totalCompareAt());
        m.put("advertised_price", advertised);
        m.put("savings", Math.round(Math.max(advertised - price, 0) * 100) / 100.0);
        m.put("is_best_price", false);
        m.put("segments", combo.segments().stream().map(Segment::toJson).collect(Collectors.toList()));
        return slot;
    }

    static String plusMinutes(String startAt, int minutes) {
        return OffsetDateTime.parse(startAt).toInstant().plus(Duration.ofMinutes(minutes)).toString();
    }

    /** Managers work 08:00-20:00 PT; outside that shift the earliest start is two hours after the next opening. */
    static Instant earliestBookableStart(Instant now) {
        ZonedDateTime local = now.atZone(SALON_TZ);
        if (local.getHour() >= SHIFT_START_HOUR && local.getHour() < SHIFT_END_HOUR) return now;
        ZonedDateTime opens = local.withHour(SHIFT_START_HOUR).withMinute(0).withSecond(0).withNano(0);
        if (local.getHour() >= SHIFT_END_HOUR) opens = opens.plusDays(1);
        return opens.plus(CONFIRMATION_GAP).toInstant();
    }
}
