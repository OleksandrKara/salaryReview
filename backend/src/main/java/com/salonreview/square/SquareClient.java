package com.salonreview.square;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.salonreview.config.SquareProperties;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin read-only client over the Square Connect v2 REST API, authenticated with the salon's access
 * token. Only the fields the salary calculation needs are mapped; unknown JSON is ignored so Square
 * API version drift doesn't break deserialization.
 *
 * <p>Money in Square is integer minor units (cents); {@link #toDollars(Money)} converts to the
 * {@link BigDecimal} dollars the rest of the app works in.
 */
@Component
public class SquareClient {

    private static final int PAGE_LIMIT = 100;

    private final RestClient http;
    private final String locationId;

    public SquareClient(SquareProperties props) {
        // Square's JSON is snake_case. Use a mapper dedicated to this client so we don't change the
        // app's own camelCase REST contract (the frontend depends on it).
        ObjectMapper squareMapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .build();

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(props.apiBaseUrl())
                .defaultHeader("Authorization", "Bearer " + props.getAccessToken())
                .defaultHeader("Content-Type", "application/json")
                .messageConverters(converters -> {
                    converters.clear();
                    converters.add(new MappingJackson2HttpMessageConverter(squareMapper));
                    converters.add(new StringHttpMessageConverter());
                });
        if (props.getApiVersion() != null && !props.getApiVersion().isBlank()) {
            builder.defaultHeader("Square-Version", props.getApiVersion());
        }
        this.http = builder.build();
        this.locationId = props.getLocationId();
    }

    /** Active team members (the providers, as Square knows them). */
    public List<TeamMember> activeTeamMembers() {
        var body = Map.of("query", Map.of("filter", Map.of("status", "ACTIVE")));
        return searchTeamMembers(body);
    }

    /** All team members, including deactivated ones (so historical bookings still resolve a name). */
    public List<TeamMember> allTeamMembers() {
        return searchTeamMembers(Map.of());
    }

    private List<TeamMember> searchTeamMembers(Object body) {
        TeamMemberSearchResponse resp = http.post()
                .uri("/v2/team-members/search")
                .body(body)
                .retrieve()
                .body(TeamMemberSearchResponse.class);
        return resp == null || resp.teamMembers() == null ? List.of() : resp.teamMembers();
    }

    /** IANA timezone of the configured location (e.g. "America/Los_Angeles"), for local-day bucketing. */
    public String locationTimeZone() {
        LocationResponse resp = http.get()
                .uri("/v2/locations/{id}", locationId)
                .retrieve()
                .body(LocationResponse.class);
        return resp == null || resp.location() == null ? null : resp.location().timezone();
    }

    /**
     * All bookings whose start falls in [start, end), following pagination. The Bookings API caps a
     * single query at 31 days, so the range is fetched in &le;30-day chunks and de-duplicated by id.
     */
    public List<Booking> bookings(Instant start, Instant end) {
        Map<String, Booking> byId = new LinkedHashMap<>();
        Instant windowStart = start;
        while (windowStart.isBefore(end)) {
            Instant windowEnd = windowStart.plus(Duration.ofDays(30));
            if (windowEnd.isAfter(end)) windowEnd = end;
            for (Booking b : bookingsWindow(windowStart, windowEnd)) byId.putIfAbsent(b.id(), b);
            windowStart = windowEnd;
        }
        return new ArrayList<>(byId.values());
    }

    private List<Booking> bookingsWindow(Instant start, Instant end) {
        List<Booking> all = new ArrayList<>();
        String cursor = null;
        do {
            final String c = cursor;
            BookingsListResponse resp = http.get()
                    .uri(b -> {
                        b.path("/v2/bookings")
                                .queryParam("location_id", locationId)
                                .queryParam("start_at_min", start.toString())
                                .queryParam("start_at_max", end.toString())
                                .queryParam("limit", PAGE_LIMIT);
                        if (c != null) b.queryParam("cursor", c);
                        return b.build();
                    })
                    .retrieve()
                    .body(BookingsListResponse.class);
            if (resp != null && resp.bookings() != null) all.addAll(resp.bookings());
            cursor = resp == null ? null : resp.cursor();
        } while (cursor != null && !cursor.isBlank());
        return all;
    }

    /** Completed orders closed in [start, end) for the configured location, following pagination. */
    public List<Order> completedOrders(Instant start, Instant end) {
        List<Order> all = new ArrayList<>();
        String cursor = null;
        do {
            Map<String, Object> body = new HashMap<>();
            body.put("location_ids", List.of(locationId));
            body.put("limit", PAGE_LIMIT);
            if (cursor != null) body.put("cursor", cursor);
            body.put("query", Map.of(
                    "filter", Map.of(
                            "date_time_filter", Map.of("closed_at",
                                    Map.of("start_at", start.toString(), "end_at", end.toString())),
                            "state_filter", Map.of("states", List.of("COMPLETED"))),
                    "sort", Map.of("sort_field", "CLOSED_AT", "sort_order", "ASC")));

            OrderSearchResponse resp = http.post()
                    .uri("/v2/orders/search")
                    .body(body)
                    .retrieve()
                    .body(OrderSearchResponse.class);
            if (resp != null && resp.orders() != null) all.addAll(resp.orders());
            cursor = resp == null ? null : resp.cursor();
        } while (cursor != null && !cursor.isBlank());
        return all;
    }

    /** Catalog list price per service-variation id, for the price-cutoff counting. */
    public Map<String, BigDecimal> catalogPrices(Collection<String> variationIds) {
        Map<String, BigDecimal> prices = new HashMap<>();
        List<String> ids = variationIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        if (ids.isEmpty()) return prices;

        CatalogBatchRetrieveResponse resp = http.post()
                .uri("/v2/catalog/batch-retrieve")
                .body(Map.of("object_ids", ids))
                .retrieve()
                .body(CatalogBatchRetrieveResponse.class);
        if (resp == null || resp.objects() == null) return prices;
        for (CatalogObject obj : resp.objects()) {
            if (obj.itemVariationData() != null && obj.itemVariationData().priceMoney() != null) {
                prices.put(obj.id(), toDollars(obj.itemVariationData().priceMoney()));
            }
        }
        return prices;
    }

    /** Display name per service-variation id (for labelling bookings, which carry no service name). */
    public Map<String, String> catalogNames(Collection<String> variationIds) {
        Map<String, String> names = new HashMap<>();
        List<String> ids = variationIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        if (ids.isEmpty()) return names;

        CatalogBatchRetrieveResponse resp = http.post()
                .uri("/v2/catalog/batch-retrieve")
                .body(Map.of("object_ids", ids))
                .retrieve()
                .body(CatalogBatchRetrieveResponse.class);
        if (resp == null || resp.objects() == null) return names;
        for (CatalogObject obj : resp.objects()) {
            if (obj.itemVariationData() != null && obj.itemVariationData().name() != null) {
                names.put(obj.id(), obj.itemVariationData().name());
            }
        }
        return names;
    }

    // Square's bulk-retrieve-customers endpoint 404s on this account, so names are fetched one GET
    // each — but cached process-wide (names rarely change) and the misses fetched in parallel, so a
    // month's worth of customers only ever costs one round of lookups.
    private final Map<String, String> customerNameCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** Display names for the given customer ids. Best-effort, cached; blanks for any we can't resolve. */
    public Map<String, String> customerNames(Collection<String> customerIds) {
        List<String> ids = customerIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        List<String> missing = ids.stream().filter(id -> !customerNameCache.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            // Empty string is cached for "looked up, no name" so we don't refetch it.
            missing.parallelStream().forEach(id -> customerNameCache.put(id, fetchCustomerName(id)));
        }
        Map<String, String> names = new HashMap<>();
        for (String id : ids) {
            String n = customerNameCache.get(id);
            if (n != null && !n.isEmpty()) names.put(id, n);
        }
        return names;
    }

    private String fetchCustomerName(String id) {
        try {
            CustomerResponse resp = http.get().uri("/v2/customers/{id}", id).retrieve().body(CustomerResponse.class);
            if (resp != null && resp.customer() != null) {
                return ((resp.customer().givenName() == null ? "" : resp.customer().givenName()) + " "
                        + (resp.customer().familyName() == null ? "" : resp.customer().familyName())).trim();
            }
        } catch (RuntimeException ignored) {
            // unresolvable — fall through to empty
        }
        return "";
    }

    /** Convert Square minor units (cents) to dollars; null money is treated as zero. */
    public static BigDecimal toDollars(Money money) {
        if (money == null || money.amount() == null) return BigDecimal.ZERO;
        return BigDecimal.valueOf(money.amount(), 2);
    }

    // --- Response models (only the fields we use; unknown JSON ignored) ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Money(Long amount, String currency) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TeamMember(String id, String givenName, String familyName, String status,
                             Boolean isOwner, String emailAddress, WageSetting wageSetting) {
        public String fullName() {
            return ((givenName == null ? "" : givenName) + " " + (familyName == null ? "" : familyName)).trim();
        }

        public boolean owner() {
            return Boolean.TRUE.equals(isOwner);
        }

        /** The team member's Square job title (from their first job assignment), or null. */
        public String jobTitle() {
            if (wageSetting == null || wageSetting.jobAssignments() == null
                    || wageSetting.jobAssignments().isEmpty()) {
                return null;
            }
            return wageSetting.jobAssignments().get(0).jobTitle();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CustomerResponse(Customer customer) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Customer(String id, String givenName, String familyName) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record WageSetting(List<JobAssignment> jobAssignments) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record JobAssignment(String jobTitle, String jobId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TeamMemberSearchResponse(List<TeamMember> teamMembers) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AppointmentSegment(String teamMemberId, String serviceVariationId, Integer durationMinutes) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Booking(String id, String status, String startAt, String locationId, String customerId,
                          String sellerNote, String customerNote, List<AppointmentSegment> appointmentSegments) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record BookingsListResponse(List<Booking> bookings, String cursor) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record OrderLineItem(String uid, String name, String quantity, String catalogObjectId,
                                Money basePriceMoney, Money grossSalesMoney, Money totalMoney,
                                Money totalDiscountMoney) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Tender(String id, String type, Money amountMoney) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Order(String id, String locationId, String customerId, String state, String closedAt,
                        String createdAt, List<OrderLineItem> lineItems, Money totalTipMoney,
                        Money totalDiscountMoney, List<Tender> tenders) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Location(String id, String name, String timezone, String currency) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record LocationResponse(Location location) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record OrderSearchResponse(List<Order> orders, String cursor) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CatalogItemVariationData(String name, String itemId, Money priceMoney) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CatalogObject(String type, String id, CatalogItemVariationData itemVariationData) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CatalogBatchRetrieveResponse(List<CatalogObject> objects) {}
}
