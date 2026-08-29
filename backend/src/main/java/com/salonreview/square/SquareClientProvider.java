package com.salonreview.square;

import com.salonreview.config.BusinessSetupIncompleteException;
import com.salonreview.config.SquareCredentialCipher;
import com.salonreview.config.SquareProperties;
import com.salonreview.domain.SquareConnection;
import com.salonreview.repo.SquareConnectionRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry/factory of {@link SquareClient} instances keyed by {@code businessId} — the multi-tenant
 * replacement for the single {@code @Component SquareClient} singleton (design.md D5/D8.3).
 * {@code SquareClient}'s own internals (its short-TTL data cache, {@code Semaphore(6)} outbound
 * throttle) are unchanged; correctness for multi-tenancy falls out entirely from "one instance per
 * business" — each business gets its own cache and its own throttle, so one business's traffic can
 * never exhaust another's rate-limit budget or serve another's cached data.
 *
 * <p>Constructed instances are themselves cached for {@link #CLIENT_TTL} so a hot request path
 * doesn't decrypt the access token on every call, but does pick up a credential rotation (Square
 * reconnect) within that window without a restart. {@link #forget(Long)} forces an immediate
 * rebuild — call it right after a business reconnects its Square account.
 *
 * <p>{@code customerCachesByBusiness} is deliberately kept outside {@code CachedClient} — a
 * rebuilt client still uses the same customer directory (customer names/ids don't depend on which
 * access token fetched them), so there's no correctness reason for a 30-minute credential-rotation
 * rebuild to also throw away that resolution cache. Found live 2026-08-29: after the Phase 2
 * mirror cutover made bookings/orders/payments effectively free, this rebuild became the dominant
 * remaining cause of a slow /reports load — {@code SquareMonthAggregator}'s still-live
 * {@code canonicalCustomerIds}/{@code customerNames} calls were quietly re-fetching every distinct
 * customer of the month, live, once per rebuild.
 */
@Component
public class SquareClientProvider {

    private static final Duration CLIENT_TTL = Duration.ofMinutes(30);

    private final SquareConnectionRepository connections;
    private final SquareCredentialCipher cipher;
    private final Map<Long, CachedClient> clients = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, SquareClient.Customer>> customerCachesByBusiness = new ConcurrentHashMap<>();

    public SquareClientProvider(SquareConnectionRepository connections, SquareCredentialCipher cipher) {
        this.connections = connections;
        this.cipher = cipher;
    }

    public SquareClient forBusiness(Long businessId) {
        CachedClient cached = clients.get(businessId);
        if (cached != null && cached.expiresAt.isAfter(Instant.now())) {
            return cached.client;
        }
        return clients.compute(businessId, (id, existing) -> {
            if (existing != null && existing.expiresAt.isAfter(Instant.now())) return existing;
            return new CachedClient(buildClient(id), Instant.now().plus(CLIENT_TTL));
        }).client;
    }

    /** Forces the next {@link #forBusiness(Long)} call for this business to rebuild from the
     * database instead of serving a cached instance — never touches any other business's client. */
    public void forget(Long businessId) {
        clients.remove(businessId);
    }

    private SquareClient buildClient(Long businessId) {
        SquareConnection connection = connections.findByBusinessId(businessId)
                .orElseThrow(() -> new BusinessSetupIncompleteException(
                        "square_not_connected",
                        "Connect Square before viewing reports or syncing data."));

        SquareProperties props = new SquareProperties();
        props.setEnvironment(connection.getEnvironment());
        props.setAccessToken(cipher.decrypt(connection.getAccessTokenEncrypted()));
        props.setLocationId(connection.getLocationId());
        Map<String, SquareClient.Customer> customerCache =
                customerCachesByBusiness.computeIfAbsent(businessId, id -> new ConcurrentHashMap<>());
        return new SquareClient(props, customerCache);
    }

    private record CachedClient(SquareClient client, Instant expiresAt) {}
}
