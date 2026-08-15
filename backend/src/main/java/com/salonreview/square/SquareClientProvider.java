package com.salonreview.square;

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
 */
@Component
public class SquareClientProvider {

    private static final Duration CLIENT_TTL = Duration.ofMinutes(30);

    private final SquareConnectionRepository connections;
    private final SquareCredentialCipher cipher;
    private final Map<Long, CachedClient> clients = new ConcurrentHashMap<>();

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
                .orElseThrow(() -> new IllegalStateException(
                        "No Square connection configured for business " + businessId));

        SquareProperties props = new SquareProperties();
        props.setEnvironment(connection.getEnvironment());
        props.setAccessToken(cipher.decrypt(connection.getAccessTokenEncrypted()));
        props.setLocationId(connection.getLocationId());
        return new SquareClient(props);
    }

    private record CachedClient(SquareClient client, Instant expiresAt) {}
}
