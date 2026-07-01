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

    // Short-TTL cache of read-only Square data. A single settlement render pulls the same windows several
    // times over (the month aggregator, the no-show detection, and the no-show panel all hit overlapping
    // bookings/orders/team-members), and switching months re-pulls everything; without this each is a
    // fresh round of paginated HTTP. Brief staleness is fine — the UI shows a "synced" timestamp and a
    // Sync button. TTLs, the sync endpoint and the freshness model are documented in docs/CACHING.md.
    private record Cached<T>(T value, long expiresAtNanos) {}
    private final Map<String, Cached<?>> cache = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile Instant lastFetchAt = Instant.now();

    @SuppressWarnings("unchecked")
    private <T> T cached(String key, Duration ttl, java.util.function.Supplier<T> loader) {
        Cached<?> c = cache.get(key);
        long now = System.nanoTime();
        if (c != null && c.expiresAtNanos() > now) return (T) c.value();
        T value = loader.get();
        cache.put(key, new Cached<>(value, now + ttl.toNanos()));
        lastFetchAt = Instant.now();
        return value;
    }

    /** When the most recent live Square pull happened (cache miss) — for an honest "synced N ago" badge. */
    public Instant lastFetchAt() { return lastFetchAt; }

    /** Drop all cached Square reads so the next call pulls fresh — backs the on-demand "Sync now" action. */
    public void invalidate() { cache.clear(); }

    /** Active team members (the providers, as Square knows them). */
    public List<TeamMember> activeTeamMembers() {
        return cached("teamMembers:active", Duration.ofMinutes(5),
                () -> searchTeamMembers(Map.of("query", Map.of("filter", Map.of("status", "ACTIVE")))));
    }

    /** All team members, including deactivated ones (so historical bookings still resolve a name). */
    public List<TeamMember> allTeamMembers() {
        return cached("teamMembers:all", Duration.ofMinutes(5), () -> searchTeamMembers(Map.of()));
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
        return cached("locationTimeZone", Duration.ofHours(1), () -> {
            LocationResponse resp = http.get()
                    .uri("/v2/locations/{id}", locationId)
                    .retrieve()
                    .body(LocationResponse.class);
            return resp == null || resp.location() == null ? null : resp.location().timezone();
        });
    }

    /**
     * All bookings whose start falls in [start, end), following pagination. The Bookings API caps a
     * single query at 31 days, so the range is fetched in &le;30-day chunks and de-duplicated by id.
     */
    public List<Booking> bookings(Instant start, Instant end) {
        return cached("bookings:" + start + ":" + end, Duration.ofMinutes(10), () -> {
            Map<String, Booking> byId = new LinkedHashMap<>();
            Instant windowStart = start;
            while (windowStart.isBefore(end)) {
                Instant windowEnd = windowStart.plus(Duration.ofDays(30));
                if (windowEnd.isAfter(end)) windowEnd = end;
                for (Booking b : bookingsWindow(windowStart, windowEnd)) byId.putIfAbsent(b.id(), b);
                windowStart = windowEnd;
            }
            return new ArrayList<>(byId.values());
        });
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
        return cached("orders:" + start + ":" + end, Duration.ofMinutes(10), () -> completedOrdersUncached(start, end));
    }

    private List<Order> completedOrdersUncached(Instant start, Instant end) {
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
        List<String> ids = variationIds.stream().filter(id -> id != null && !id.isBlank()).distinct().sorted().toList();
        if (ids.isEmpty()) return new HashMap<>();
        return cached("catalogPrices:" + ids, Duration.ofMinutes(10), () -> {
            Map<String, BigDecimal> prices = new HashMap<>();
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
        });
    }

    /**
     * Display name per service-variation id, formatted for human reading. Combines the parent
     * item name with the variation name when both exist (e.g. {@code "Pedicure · Nail Artist"}),
     * which is much more useful for triage than just the variation name. Falls back gracefully if
     * either side is missing or generic.
     */
    public Map<String, String> catalogNames(Collection<String> variationIds) {
        List<String> ids = variationIds.stream().filter(id -> id != null && !id.isBlank()).distinct().sorted().toList();
        if (ids.isEmpty()) return new HashMap<>();
        return cached("catalogNames:" + ids, Duration.ofMinutes(10), () -> {
            Map<String, String> names = new HashMap<>();
            CatalogBatchRetrieveResponse resp = http.post()
                    .uri("/v2/catalog/batch-retrieve")
                    // include_related_objects=true returns the parent items alongside the requested
                    // variations in `related_objects` — one HTTP call instead of two.
                    .body(Map.of("object_ids", ids, "include_related_objects", true))
                    .retrieve()
                    .body(CatalogBatchRetrieveResponse.class);
            if (resp == null || resp.objects() == null) return names;

            // Build itemId → item name map from related_objects.
            Map<String, String> itemNames = new HashMap<>();
            if (resp.relatedObjects() != null) {
                for (CatalogObject obj : resp.relatedObjects()) {
                    if (obj.itemData() != null && obj.itemData().name() != null) {
                        itemNames.put(obj.id(), obj.itemData().name());
                    }
                }
            }

            for (CatalogObject obj : resp.objects()) {
                if (obj.itemVariationData() == null) continue;
                String varName = obj.itemVariationData().name();
                String itemName = obj.itemVariationData().itemId() == null
                        ? null
                        : itemNames.get(obj.itemVariationData().itemId());
                String combined = combineCatalogName(itemName, varName);
                if (combined != null) names.put(obj.id(), combined);
            }
            return names;
        });
    }

    /**
     * Format a parent-item + variation name pair for display. Skips redundant pieces:
     * if the variation is a generic placeholder (e.g. "Regular") we just show the item name;
     * if the two are the same we don't duplicate; if one is missing we show the other.
     */
    static String combineCatalogName(String itemName, String variationName) {
        boolean hasItem = itemName != null && !itemName.isBlank();
        boolean hasVar = variationName != null && !variationName.isBlank();
        if (!hasItem && !hasVar) return null;
        if (!hasItem) return variationName.trim();
        if (!hasVar) return itemName.trim();
        String i = itemName.trim();
        String v = variationName.trim();
        if (i.equalsIgnoreCase(v)) return i;
        String vLower = v.toLowerCase(java.util.Locale.US);
        // Square's default variation name is "Regular" when the salon didn't customize it —
        // surfacing that adds no information, so we suppress it.
        if (vLower.equals("regular") || vLower.equals("standard") || vLower.equals("default")) return i;
        return i + " · " + v;
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

    /**
     * Customers whose name contains {@code query} (case-insensitive). The Customers API has no name
     * search, so we page the whole directory and filter client-side, returning up to 25 matches.
     * (The page bound is only a safety net for a pathological directory; see the loop.)
     */
    public List<Customer> searchCustomers(String query) {
        String q = query == null ? "" : query.trim().toLowerCase();
        if (q.isEmpty()) return List.of();
        List<Customer> matches = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            final String c = cursor;
            CustomersListResponse resp = http.get()
                    .uri(b -> {
                        b.path("/v2/customers").queryParam("limit", 100);
                        if (c != null) b.queryParam("cursor", c);
                        return b.build();
                    })
                    .retrieve()
                    .body(CustomersListResponse.class);
            if (resp != null && resp.customers() != null) {
                for (Customer cust : resp.customers()) {
                    String name = ((cust.givenName() == null ? "" : cust.givenName()) + " "
                            + (cust.familyName() == null ? "" : cust.familyName())).trim();
                    if (name.toLowerCase().contains(q)) matches.add(cust);
                    if (matches.size() >= 25) return matches;
                }
            }
            cursor = resp == null ? null : resp.cursor();
            // Scan the whole customer directory (the Customers API has no name filter, so we list +
            // match client-side). Stops early once 25 matches are found; the page bound is just a
            // safety net against a pathological directory (100 pages = 10,000 customers).
        } while (cursor != null && !cursor.isBlank() && ++pages < 100);
        return matches;
    }

    /** Invoices issued to a customer (most recent first), for picking the prepaid invoice. */
    public List<Invoice> invoicesForCustomer(String customerId) {
        if (customerId == null || customerId.isBlank()) return List.of();
        Map<String, Object> body = Map.of(
                "query", Map.of(
                        "filter", Map.of("location_ids", List.of(locationId),
                                "customer_ids", List.of(customerId)),
                        "sort", Map.of("field", "INVOICE_SORT_DATE", "order", "DESC")),
                "limit", 100);
        InvoiceSearchResponse resp = http.post()
                .uri("/v2/invoices/search")
                .body(body)
                .retrieve()
                .body(InvoiceSearchResponse.class);
        return resp == null || resp.invoices() == null ? List.of() : resp.invoices();
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

    /**
     * Whether an order was paid mostly in cash — its cash tenders outweigh its non-cash tenders. The
     * single source of truth for the cash/card split, shared by the month aggregator and the revenue
     * pulse so both attribute tender the same way.
     */
    public static boolean isCashOrder(Order o) {
        if (o == null || o.tenders() == null) return false;
        BigDecimal cash = BigDecimal.ZERO, other = BigDecimal.ZERO;
        for (Tender t : o.tenders()) {
            BigDecimal amt = toDollars(t.amountMoney());
            if ("CASH".equals(t.type())) cash = cash.add(amt);
            else other = other.add(amt);
        }
        return cash.compareTo(other) > 0;
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
    public record Customer(String id, String givenName, String familyName) {
        public String fullName() {
            return ((givenName == null ? "" : givenName) + " " + (familyName == null ? "" : familyName)).trim();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CustomersListResponse(List<Customer> customers, String cursor) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Invoice(String id, String invoiceNumber, String title, String status, String createdAt,
                          List<PaymentRequest> paymentRequests) {
        /** The invoice total (sum of its payment requests' computed amounts), in dollars. */
        public BigDecimal total() {
            if (paymentRequests == null) return BigDecimal.ZERO;
            BigDecimal t = BigDecimal.ZERO;
            for (PaymentRequest pr : paymentRequests) t = t.add(toDollars(pr.computedAmountMoney()));
            return t;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PaymentRequest(Money computedAmountMoney) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record InvoiceSearchResponse(List<Invoice> invoices, String cursor) {}

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
    public record Booking(String id, String status, String startAt, String createdAt, String updatedAt,
                          String locationId, String customerId, String sellerNote, String customerNote,
                          List<AppointmentSegment> appointmentSegments) {}

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

    /** Parent catalog item — Square models a "service" as an item with one or more variations. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CatalogItemData(String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CatalogObject(String type, String id,
                                CatalogItemVariationData itemVariationData,
                                CatalogItemData itemData) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CatalogBatchRetrieveResponse(List<CatalogObject> objects,
                                               List<CatalogObject> relatedObjects) {}
}
