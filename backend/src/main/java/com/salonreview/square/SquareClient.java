package com.salonreview.square;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.salonreview.config.SquareProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    private static final Logger log = LoggerFactory.getLogger(SquareClient.class);

    private static final int PAGE_LIMIT = 100;

    private final RestClient http;
    private final String locationId;

    @Autowired
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

    /** Test-only constructor — points this client at an arbitrary {@link RestClient} (e.g. a local
     * fake server) instead of building one from real {@link SquareProperties}/credentials. */
    SquareClient(RestClient http, String locationId) {
        this.http = http;
        this.locationId = locationId;
    }

    // Bounded concurrency for outbound Square calls — see docs/CACHING.md. bookingsForCustomer's
    // per-window virtual-thread fan-out, and parallelStream() over contacts/customers in
    // MarketingAnalyticsService/MarketingContactsService, deliberately issue many Square calls at
    // once to keep page loads fast — but with no cap that fan-out can burst past 100 simultaneous
    // requests for a single page load (N customers x ~20 booking-history windows each) and trip
    // Square's real per-merchant RATE_LIMIT_ERROR. Every raw HTTP call in this client passes
    // through this single gate, so every caller is protected uniformly without needing to
    // coordinate limits themselves.
    static final int MAX_CONCURRENT_SQUARE_CALLS = 6; // package-private: used by SquareClientConcurrencyTest
    private final java.util.concurrent.Semaphore squareCallPermits =
            new java.util.concurrent.Semaphore(MAX_CONCURRENT_SQUARE_CALLS, /* fair */ true);

    private <T> T throttled(java.util.function.Supplier<T> call) {
        boolean acquired = false;
        try {
            squareCallPermits.acquire();
            acquired = true;
            return call.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for a Square call permit", e);
        } finally {
            if (acquired) squareCallPermits.release();
        }
    }

    // Short-TTL cache of read-only Square data. A single settlement render pulls the same windows several
    // times over (the month aggregator, the no-show detection, and the no-show panel all hit overlapping
    // bookings/orders/team-members), and switching months re-pulls everything; without this each is a
    // fresh round of paginated HTTP. Brief staleness is fine — the UI shows a "synced" timestamp and a
    // Sync button. TTLs, the sync endpoint, the freshness model, and the outbound concurrency limit
    // (below) are documented in docs/CACHING.md.
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
        TeamMemberSearchResponse resp = throttled(() -> http.post()
                .uri("/v2/team-members/search")
                .body(body)
                .retrieve()
                .body(TeamMemberSearchResponse.class));
        return resp == null || resp.teamMembers() == null ? List.of() : resp.teamMembers();
    }

    /** IANA timezone of the configured location (e.g. "America/Los_Angeles"), for local-day bucketing. */
    public String locationTimeZone() {
        return cached("locationTimeZone", Duration.ofHours(1), () -> {
            LocationResponse resp = throttled(() -> http.get()
                    .uri("/v2/locations/{id}", locationId)
                    .retrieve()
                    .body(LocationResponse.class));
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
            BookingsListResponse resp = throttled(() -> http.get()
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
                    .body(BookingsListResponse.class));
            if (resp != null && resp.bookings() != null) all.addAll(resp.bookings());
            cursor = resp == null ? null : resp.cursor();
        } while (cursor != null && !cursor.isBlank());
        return all;
    }

    /** How far past "now" to keep looking for a customer's upcoming bookings — a nail salon's
     * scheduling horizon rarely runs longer than this. */
    private static final Duration FUTURE_BOOKING_HORIZON = Duration.ofDays(180);

    /** Every booking (past and upcoming) for one Square customer, most recent first, from
     * {@code since} through {@link #FUTURE_BOOKING_HORIZON} past now.
     *
     * <p>{@code since} is required, not optional: Square's {@code GET /v2/bookings} silently
     * defaults to "now onward" when {@code start_at_min}/{@code start_at_max} are omitted — it
     * does NOT return full history. (Confirmed directly against Square's production API: a
     * customer_id-filtered query with no date bound returned zero bookings for a customer who, in
     * fact, had two — both already in the past.) Passing an explicit bound, chunked into
     * &le;30-day windows the same way {@link #bookings(Instant, Instant)} already does for the
     * location-wide query, is the only way to actually see completed/cancelled/no-show history.
     *
     * <p>Short TTL: called lazily per-contact from the marketing Contacts page, not in a hot path,
     * but still worth not re-fetching on every render while that page is open.
     */
    public List<Booking> bookingsForCustomer(String customerId, Instant since) {
        if (customerId == null || customerId.isBlank()) return List.of();
        Instant end = Instant.now().plus(FUTURE_BOOKING_HORIZON);
        Instant start = since.isAfter(end) ? end : since;
        return cached("bookingsForCustomer:" + customerId + ":" + start, Duration.ofMinutes(2), () -> {
            List<Instant> windowStarts = new ArrayList<>();
            Instant windowStart = start;
            while (windowStart.isBefore(end)) {
                windowStarts.add(windowStart);
                windowStart = windowStart.plus(Duration.ofDays(30));
            }
            // Each window is its own blocking Square HTTP call, and a customer whose lead is a
            // few months old can need a dozen-plus of them (see the class doc above) — fanning
            // them out on virtual threads instead of one at a time is what keeps the Contacts/
            // Analytics/Overview pages from taking many seconds to load per contact.
            Map<String, Booking> byId = new LinkedHashMap<>();
            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                List<java.util.concurrent.Future<List<Booking>>> futures = windowStarts.stream()
                        .map(ws -> executor.submit(() -> {
                            Instant we = ws.plus(Duration.ofDays(30));
                            return bookingsForCustomerWindow(customerId, ws, we.isAfter(end) ? end : we);
                        }))
                        .toList();
                for (var future : futures) {
                    for (Booking b : awaitBookingsOrEmpty(customerId, future)) byId.putIfAbsent(b.id(), b);
                }
            }
            List<Booking> all = new ArrayList<>(byId.values());
            all.sort((a, b) -> {
                if (a.startAt() == null) return 1;
                if (b.startAt() == null) return -1;
                return b.startAt().compareTo(a.startAt());
            });
            return all;
        });
    }

    /** Waits for one booking-history window's fetch. A window that fails outright (e.g. a Square
     * rate-limit error that survives the {@link #throttled} gate) degrades to "no bookings from
     * this window" rather than failing the customer's entire booking history — the other windows'
     * results are still returned. Trade-off: if the *oldest* window happens to be the one that
     * fails, a customer's true earliest booking could be missed, which could misclassify them as
     * fresher than they really are in {@code MarketingAnalyticsService.isFresh}. That's judged an
     * acceptable, rare, non-systematic risk against the alternative — one failed window taking down
     * the whole response for every customer on the page. Interruption (shutdown, not a Square
     * error) still fails fast.
     */
    private static List<Booking> awaitBookingsOrEmpty(String customerId, java.util.concurrent.Future<List<Booking>> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (java.util.concurrent.ExecutionException e) {
            log.warn("Failed to fetch one booking-history window for customer {}; returning partial history", customerId, e.getCause());
            return List.of();
        }
    }

    private List<Booking> bookingsForCustomerWindow(String customerId, Instant start, Instant end) {
        List<Booking> all = new ArrayList<>();
        String cursor = null;
        do {
            final String c = cursor;
            BookingsListResponse resp = throttled(() -> http.get()
                    .uri(b -> {
                        b.path("/v2/bookings")
                                .queryParam("location_id", locationId)
                                .queryParam("customer_id", customerId)
                                .queryParam("start_at_min", start.toString())
                                .queryParam("start_at_max", end.toString())
                                .queryParam("limit", PAGE_LIMIT);
                        if (c != null) b.queryParam("cursor", c);
                        return b.build();
                    })
                    .retrieve()
                    .body(BookingsListResponse.class));
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

            OrderSearchResponse resp = throttled(() -> http.post()
                    .uri("/v2/orders/search")
                    .body(body)
                    .retrieve()
                    .body(OrderSearchResponse.class));
            if (resp != null && resp.orders() != null) all.addAll(resp.orders());
            cursor = resp == null ? null : resp.cursor();
        } while (cursor != null && !cursor.isBlank());
        return all;
    }

    /**
     * Payments recorded in [start, end) for the configured location, following pagination — including
     * any payment not linked to an Order (e.g. a card charged directly against a customer's card on
     * file from their profile, bypassing the booking checkout flow). The Orders API never sees these;
     * this is the only Square read that does. Used to detect "orphan" payments the order-based
     * reconciliation in {@link SquareMonthAggregator} would otherwise silently miss — see
     * {@code openspec/changes/multi-tenant-salon-platform/} P0 payment-accounting findings.
     */
    public List<Payment> payments(Instant start, Instant end) {
        return cached("payments:" + start + ":" + end, Duration.ofMinutes(10), () -> paymentsUncached(start, end));
    }

    private List<Payment> paymentsUncached(Instant start, Instant end) {
        List<Payment> all = new ArrayList<>();
        String cursor = null;
        do {
            final String c = cursor;
            PaymentsListResponse resp = throttled(() -> http.get()
                    .uri(b -> {
                        b.path("/v2/payments")
                                .queryParam("location_id", locationId)
                                .queryParam("begin_time", start.toString())
                                .queryParam("end_time", end.toString())
                                .queryParam("sort_order", "ASC")
                                .queryParam("limit", PAGE_LIMIT);
                        if (c != null) b.queryParam("cursor", c);
                        return b.build();
                    })
                    .retrieve()
                    .body(PaymentsListResponse.class));
            if (resp != null && resp.payments() != null) all.addAll(resp.payments());
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
            CatalogBatchRetrieveResponse resp = throttled(() -> http.post()
                    .uri("/v2/catalog/batch-retrieve")
                    .body(Map.of("object_ids", ids))
                    .retrieve()
                    .body(CatalogBatchRetrieveResponse.class));
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
            CatalogBatchRetrieveResponse resp = throttled(() -> http.post()
                    .uri("/v2/catalog/batch-retrieve")
                    // include_related_objects=true returns the parent items alongside the requested
                    // variations in `related_objects` — one HTTP call instead of two.
                    .body(Map.of("object_ids", ids, "include_related_objects", true))
                    .retrieve()
                    .body(CatalogBatchRetrieveResponse.class));
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

    // Square's bulk-retrieve-customers endpoint 404s on this account, so customers are fetched one GET
    // each — but cached process-wide (names/creation dates never change) and the misses fetched in
    // parallel, so a month's worth of customers only ever costs one round of lookups. A sentinel
    // "not found" Customer (all-null fields) is cached too, so a bad id isn't refetched forever.
    private static final Customer NOT_FOUND = new Customer(null, null, null, null, null, null);
    private final Map<String, Customer> customerCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** Display names for the given customer ids. Best-effort, cached; blanks for any we can't resolve. */
    public Map<String, String> customerNames(Collection<String> customerIds) {
        Map<String, String> names = new HashMap<>();
        for (var e : fetchCustomers(customerIds).entrySet()) {
            String n = e.getValue().fullName();
            if (!n.isEmpty()) names.put(e.getKey(), n);
        }
        return names;
    }

    /** Given (first) names only — never concatenated with family name, unlike {@link
     * #customerNames}. Use this, not {@code customerNames}, anywhere the result can feed a
     * customer-facing message (an SMS greeting should always read "Hi Jane," never "Hi Jane
     * Smith,") — see CheckoutReviewTriggerService. Best-effort, cached; missing for any we can't
     * resolve. */
    public Map<String, String> customerGivenNames(Collection<String> customerIds) {
        Map<String, String> names = new HashMap<>();
        for (var e : fetchCustomers(customerIds).entrySet()) {
            String n = e.getValue().givenName();
            if (n != null && !n.isBlank()) names.put(e.getKey(), n);
        }
        return names;
    }

    /** Family (last) names only, for display purposes (e.g. the Messages conversation list) —
     * never used for an SMS greeting. Best-effort, cached; missing for any we can't resolve. */
    public Map<String, String> customerFamilyNames(Collection<String> customerIds) {
        Map<String, String> names = new HashMap<>();
        for (var e : fetchCustomers(customerIds).entrySet()) {
            String n = e.getValue().familyName();
            if (n != null && !n.isBlank()) names.put(e.getKey(), n);
        }
        return names;
    }

    /** When each customer's Square record was created. Best-effort, cached; missing for any we can't
     * resolve — used to tell a brand-new-to-Square customer from one who already existed before
     * coming back through an ad.
     */
    public Map<String, Instant> customerCreatedAts(Collection<String> customerIds) {
        Map<String, Instant> createdAts = new HashMap<>();
        for (var e : fetchCustomers(customerIds).entrySet()) {
            if (e.getValue().createdAt() == null) continue;
            try {
                createdAts.put(e.getKey(), Instant.parse(e.getValue().createdAt()));
            } catch (java.time.format.DateTimeParseException ignored) {
                // unparseable — leave this customer out rather than guess
            }
        }
        return createdAts;
    }

    /**
     * Resolves each given Square customer id to its current, canonical id. Square can silently merge
     * two duplicate customer profiles (e.g. one created via online booking, one created at the
     * register) into one surviving record; {@code GET /v2/customers/{id}} transparently redirects
     * through any such merge and returns the surviving profile (whose own {@code id} may differ from
     * the id requested). An already-written Booking or Order keeps whichever id was current at the
     * time it was created, so the very same real customer's booking and paid order can carry two
     * different, permanently un-equal ids. Resolving both through this once, up front, makes any
     * customerId-keyed matching downstream immune to that split. Best-effort: an id we can't resolve
     * maps to itself.
     */
    public Map<String, String> canonicalCustomerIds(Collection<String> customerIds) {
        Map<String, String> canonical = new HashMap<>();
        for (var e : fetchCustomers(customerIds).entrySet()) {
            String cid = e.getValue().id();
            canonical.put(e.getKey(), cid != null && !cid.isBlank() ? cid : e.getKey());
        }
        return canonical;
    }

    private Map<String, Customer> fetchCustomers(Collection<String> customerIds) {
        List<String> ids = customerIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        List<String> missing = ids.stream().filter(id -> !customerCache.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            missing.parallelStream().forEach(id -> customerCache.put(id, fetchCustomer(id).orElse(NOT_FOUND)));
        }
        Map<String, Customer> out = new HashMap<>();
        for (String id : ids) {
            Customer c = customerCache.get(id);
            if (c != null && c != NOT_FOUND) out.put(id, c);
        }
        return out;
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
            CustomersListResponse resp = throttled(() -> http.get()
                    .uri(b -> {
                        b.path("/v2/customers").queryParam("limit", 100);
                        if (c != null) b.queryParam("cursor", c);
                        return b.build();
                    })
                    .retrieve()
                    .body(CustomersListResponse.class));
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

    /**
     * Customer ids Square has on file for a phone number, via an exact-match search. A contact's
     * originally-linked square_customer_id can go stale — e.g. a follow-up appointment booked by
     * phone gets matched or created against a *different* Square profile for the same person — so
     * this is how the marketing analytics fresh/upcoming logic finds appointments under a profile
     * other than the one first captured, without re-scanning the whole customer directory.
     * Short-TTL cached like the rest of this client's reads.
     */
    public List<String> customerIdsForPhone(String phoneNumber) {
        String normalized = normalizePhone(phoneNumber);
        if (normalized == null) return List.of();
        return cached("customerIdsForPhone:" + normalized, Duration.ofMinutes(5), () -> {
            Map<String, Object> body = Map.of(
                    "query", Map.of("filter", Map.of("phone_number", Map.of("exact", normalized))));
            CustomersSearchResponse resp = throttled(() -> http.post()
                    .uri("/v2/customers/search")
                    .body(body)
                    .retrieve()
                    .body(CustomersSearchResponse.class));
            if (resp == null || resp.customers() == null) return List.<String>of();
            return resp.customers().stream().map(Customer::id).toList();
        });
    }

    /** Best-effort US phone normalization to Square's expected E.164 form; null if unrecognizable. */
    private static String normalizePhone(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() == 10) return "+1" + digits;
        if (digits.length() == 11 && digits.startsWith("1")) return "+" + digits;
        return null;
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
        InvoiceSearchResponse resp = throttled(() -> http.post()
                .uri("/v2/invoices/search")
                .body(body)
                .retrieve()
                .body(InvoiceSearchResponse.class));
        return resp == null || resp.invoices() == null ? List.of() : resp.invoices();
    }

    private java.util.Optional<Customer> fetchCustomer(String id) {
        try {
            CustomerResponse resp = throttled(() ->
                    http.get().uri("/v2/customers/{id}", id).retrieve().body(CustomerResponse.class));
            return java.util.Optional.ofNullable(resp == null ? null : resp.customer());
        } catch (RuntimeException ignored) {
            return java.util.Optional.empty(); // unresolvable
        }
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

    /** Whether an order was placed through an online booking (has a {@code BOOKING}-type
     * fulfillment) rather than rung up in-salon at the register — confirmed against real Square
     * data: a booking-driven order carries {@code fulfillments: [{type: "BOOKING", ...}]}, while a
     * walk-in POS sale has no {@code fulfillments} array at all (see
     * openspec/changes/sms-automations-hub/design.md D2). Used by the checkout-review automation to
     * fire only for in-salon checkouts. */
    public static boolean isBookingLinked(Order o) {
        return o != null && o.fulfillments() != null
                && o.fulfillments().stream().anyMatch(f -> "BOOKING".equals(f.type()));
    }

    /** A single order by id, uncached — used by the checkout-review automation's Square webhook
     * handler, which needs a fresh read at the moment of a real payment event rather than whatever
     * a TTL cache happened to have. Empty on any failure (never throws — the automation silently
     * skips rather than blocking on a transient Square error). */
    public java.util.Optional<Order> orderById(String orderId) {
        try {
            OrderResponse resp = throttled(() ->
                    http.get().uri("/v2/orders/{id}", orderId).retrieve().body(OrderResponse.class));
            return java.util.Optional.ofNullable(resp == null ? null : resp.order());
        } catch (RuntimeException e) {
            log.warn("Failed to fetch Square order {} for checkout-review automation: {}", orderId, e.getMessage());
            return java.util.Optional.empty();
        }
    }

    /** The phone number on file for a Square customer, or {@code null} if absent/unresolvable — a
     * genuinely anonymous walk-in with no profile is the expected reason for {@code null}, not an
     * error (see design.md D2). */
    public String customerPhone(String customerId) {
        return fetchCustomer(customerId).map(Customer::phoneNumber)
                .filter(p -> p != null && !p.isBlank())
                .orElse(null);
    }

    /** The Square customer-group ids this customer belongs to — used by the
     * {@code same_day_rebooking_discount} automation to check Square's own "Text Subscribers"
     * segment as an alternate consent source (see openspec/changes/same-day-rebooking-discount
     * design.md D3). Empty (not null) if the customer can't be resolved or has no segments. */
    public List<String> customerSegmentIds(String customerId) {
        return fetchCustomer(customerId).map(Customer::segmentIds)
                .filter(java.util.Objects::nonNull)
                .orElse(List.of());
    }

    /** Batched sibling of the single-customer overload above — segment ids ride along on every
     * customer fetch, so this costs nothing beyond the same fetchCustomers() cache every other
     * batch accessor here already uses. Backs the Messages page's merged SMS-consent indicator
     * (Square's own segment as an alternate consent source alongside marketing.contacts — see
     * MarketingContactsService#resolveDisplayNames). Missing (not empty-list) for any customer id
     * that can't be resolved at all. */
    public Map<String, List<String>> customerSegmentIdsBatch(Collection<String> customerIds) {
        Map<String, List<String>> result = new HashMap<>();
        for (var e : fetchCustomers(customerIds).entrySet()) {
            List<String> segments = e.getValue().segmentIds();
            if (segments != null) result.put(e.getKey(), segments);
        }
        return result;
    }

    /** Adds a customer to a Square customer group — used to enroll a customer in the same-day-
     * rebooking auto-discount group (see design.md D7). Uncached, mutating; throws on failure so
     * the caller (the internal enroll endpoint) can decide how to report it, rather than silently
     * swallowing it the way read-only lookups in this client do. */
    public void addCustomerToGroup(String customerId, String groupId) {
        throttled(() -> http.put()
                .uri("/v2/customers/{customerId}/groups/{groupId}", customerId, groupId)
                .retrieve()
                .toBodilessEntity());
    }

    /** Removes a customer from a Square customer group — used by the group-expiry sweep once a
     * customer's personal offer window passes (see design.md D7). Uncached, mutating; throws on
     * failure so the caller (the expiry scheduler) can leave the row unremoved and retry next
     * tick, matching this automation's fail-closed conventions elsewhere. */
    public void removeCustomerFromGroup(String customerId, String groupId) {
        throttled(() -> http.delete()
                .uri("/v2/customers/{customerId}/groups/{groupId}", customerId, groupId)
                .retrieve()
                .toBodilessEntity());
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
    public record Customer(String id, String givenName, String familyName, String createdAt, String phoneNumber,
                           List<String> segmentIds) {
        public String fullName() {
            return ((givenName == null ? "" : givenName) + " " + (familyName == null ? "" : familyName)).trim();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CustomersListResponse(List<Customer> customers, String cursor) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CustomersSearchResponse(List<Customer> customers, String cursor) {}

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
                          List<AppointmentSegment> appointmentSegments) {
        Booking withCustomerId(String newCustomerId) {
            return new Booking(id, status, startAt, createdAt, updatedAt, locationId, newCustomerId,
                    sellerNote, customerNote, appointmentSegments);
        }
    }

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
                        Money totalDiscountMoney, List<Tender> tenders, List<Fulfillment> fulfillments) {
        Order withCustomerId(String newCustomerId) {
            return new Order(id, locationId, newCustomerId, state, closedAt, createdAt, lineItems,
                    totalTipMoney, totalDiscountMoney, tenders, fulfillments);
        }
    }

    /** Only present on an order created via an online booking — see {@link #isBookingLinked}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Fulfillment(String type, String state) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record OrderResponse(Order order) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Location(String id, String name, String timezone, String currency) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record LocationResponse(Location location) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record OrderSearchResponse(List<Order> orders, String cursor) {}

    /** A Square Payment — {@code orderId} is null when the charge was taken directly (e.g. "charge
     * card on file" from a customer profile) rather than through an Order/checkout, which is exactly
     * the case the order-based reconciliation pipeline can't see on its own. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Payment(String id, String orderId, String customerId, String status,
                          String createdAt, Money totalMoney, Money tipMoney) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PaymentsListResponse(List<Payment> payments, String cursor) {}

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
