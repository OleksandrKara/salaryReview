package com.salonreview.square;

import com.salonreview.config.SquareCredentialCipher;
import com.salonreview.config.SquareProperties;
import com.salonreview.domain.Business;
import com.salonreview.domain.SquareConnection;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.repo.SquareConnectionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;

/**
 * Backs the owner-facing "Connect Square" settings form (Phase 6.4) — the UI path for pasting in a
 * business's Square access token, replacing the earlier practice of sharing it in chat/Slack for a
 * human to hand-enter into the database. Every connect/reconnect is validated against a real Square
 * call before anything is written, so a bad token or wrong location id fails loudly right here with
 * a clear message, not silently on the next unrelated page load.
 */
@Service
public class SquareConnectionService {

    private final SquareConnectionRepository repo;
    private final SquareCredentialCipher cipher;
    private final SquareClientProvider squareClientProvider;
    private final BusinessRepository businesses;
    private final Function<SquareProperties, SquareClient> clientFactory;

    @Autowired
    public SquareConnectionService(SquareConnectionRepository repo, SquareCredentialCipher cipher,
                                    SquareClientProvider squareClientProvider, BusinessRepository businesses) {
        this(repo, cipher, squareClientProvider, businesses, SquareClient::new);
    }

    /** Test-only — lets a unit test substitute a fake {@link SquareClient} for {@link #validate}
     * instead of {@code validate()} making a real Square call, same reasoning as
     * {@code SquareClient}'s own package-private test constructor. */
    SquareConnectionService(SquareConnectionRepository repo, SquareCredentialCipher cipher,
                             SquareClientProvider squareClientProvider, BusinessRepository businesses,
                             Function<SquareProperties, SquareClient> clientFactory) {
        this.repo = repo;
        this.cipher = cipher;
        this.squareClientProvider = squareClientProvider;
        this.businesses = businesses;
        this.clientFactory = clientFactory;
    }

    public Optional<SquareConnection> get(Long businessId) {
        return repo.findByBusinessId(businessId);
    }

    /** Decrypts the stored token purely to build a display mask ("••••" + last 4) — the plaintext
     * never leaves this method. {@code null} if nothing is connected yet. */
    public String maskedAccessToken(SquareConnection connection) {
        if (connection == null) return null;
        String token = cipher.decrypt(connection.getAccessTokenEncrypted());
        return token.length() <= 4 ? "••••" : "••••" + token.substring(token.length() - 4);
    }

    /**
     * {@code accessToken} {@code null}/blank keeps the existing encrypted token (only meaningful
     * when reconnecting to change just the location or environment); required the first time a
     * business connects. Validates the resolved token+location against a real
     * {@code GET /v2/locations/{id}} call before saving anything, and captures {@code merchantId}
     * from that same response for free (design.md D5) — and, best-effort, sets the business's own
     * timezone from Square's location record too (the owner can still override it afterward via
     * the Business Settings form; this just saves a manual step when Square already knows it).
     */
    @Transactional
    public SquareConnection connect(Long businessId, SquareProperties.Environment environment,
                                     String accessToken, String locationId, String applicationId,
                                     Long connectedByUserId) {
        SquareConnection existing = repo.findByBusinessId(businessId).orElse(null);
        boolean tokenProvided = accessToken != null && !accessToken.isBlank();
        if (!tokenProvided && existing == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "accessToken is required to connect Square for the first time");
        }
        String tokenToUse = tokenProvided ? accessToken : cipher.decrypt(existing.getAccessTokenEncrypted());

        SquareClient.Location location = validate(environment, tokenToUse, locationId);

        SquareConnection connection = existing != null ? existing : new SquareConnection();
        connection.setBusinessId(businessId);
        connection.setEnvironment(environment);
        connection.setAccessTokenEncrypted(cipher.encrypt(tokenToUse));
        connection.setLocationId(locationId);
        connection.setApplicationId(applicationId != null && !applicationId.isBlank() ? applicationId.trim() : null);
        connection.setMerchantId(location.merchantId());
        connection.setConnectedByUserId(connectedByUserId);
        connection.setConnectedAt(Instant.now());
        SquareConnection saved = repo.save(connection);

        if (location.timezone() != null && !location.timezone().isBlank()) {
            businesses.findById(businessId).ifPresent(business -> {
                business.setTimezone(location.timezone());
                businesses.save(business);
            });
        }

        // Picks up the new/rotated credentials on the very next call — no restart needed.
        squareClientProvider.forget(businessId);
        return saved;
    }

    private SquareClient.Location validate(SquareProperties.Environment environment, String accessToken,
                                            String locationId) {
        SquareProperties props = new SquareProperties();
        props.setEnvironment(environment);
        props.setAccessToken(accessToken);
        props.setLocationId(locationId);
        SquareClient.Location location;
        try {
            location = clientFactory.apply(props).location();
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Could not verify these Square credentials: " + e.getMessage(), e);
        }
        if (location == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Square accepted the request but returned no location for id " + locationId
                            + " — double-check the location id and environment (sandbox vs. production)");
        }
        return location;
    }
}
