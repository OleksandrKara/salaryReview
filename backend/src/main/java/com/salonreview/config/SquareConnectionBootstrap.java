package com.salonreview.config;

import com.salonreview.domain.AppUser;
import com.salonreview.domain.Role;
import com.salonreview.domain.SquareConnection;
import com.salonreview.repo.AppUserRepository;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.repo.SquareConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Phase 3.3 — one-time backfill of business A's existing {@code SQUARE_ACCESS_TOKEN}/
 * {@code SQUARE_LOCATION_ID} env vars into its {@code square_connection} row, so the app keeps
 * working exactly as it does today (single global {@code SquareClient} bean, unchanged until Phase
 * 3.5's call-site migration) while the table gets populated for {@link
 * com.salonreview.square.SquareClientProvider} ahead of that cutover. Idempotent — does nothing once
 * business A already has a row. Deliberately application code, not a manual DB script run once by a
 * human: it only ever re-writes credentials the app is *already* trusted with via its own env vars,
 * so it's safe to run unattended on every boot, in every environment, forever.
 */
@Configuration
public class SquareConnectionBootstrap {

    private static final Logger log = LoggerFactory.getLogger(SquareConnectionBootstrap.class);

    @Bean
    ApplicationRunner backfillBusinessASquareConnection(SquareConnectionRepository connections,
                                                          BusinessRepository businesses,
                                                          AppUserRepository users,
                                                          SquareCredentialCipher cipher,
                                                          SquareProperties squareProperties) {
        return args -> {
            if (!squareProperties.isConfigured()) {
                log.info("No SQUARE_ACCESS_TOKEN set — nothing to backfill into square_connection.");
                return;
            }
            if (!cipher.isConfigured()) {
                log.warn("SQUARE_ACCESS_TOKEN is set but SQUARE_CREDENTIALS_MASTER_KEY is not — "
                        + "skipping square_connection backfill until a master key is configured.");
                return;
            }

            List<com.salonreview.domain.Business> all = businesses.findAll();
            if (all.size() != 1) return; // same guard as BusinessRepository.sole() — nothing to do
                                          // once a second business exists; that's Phase 3.7's job.
            Long businessId = all.get(0).getId();
            if (connections.findByBusinessId(businessId).isPresent()) return;

            List<AppUser> owners = users.findByBusinessIdAndRoleInAndActiveTrueOrderByUsernameAsc(
                    businessId, List.of(Role.OWNER));
            if (owners.isEmpty()) {
                log.warn("No active OWNER account yet for business {} — deferring square_connection "
                        + "backfill to next boot.", businessId);
                return;
            }

            connections.save(SquareConnection.builder()
                    .businessId(businessId)
                    .environment(squareProperties.getEnvironment())
                    .accessTokenEncrypted(cipher.encrypt(squareProperties.getAccessToken()))
                    .locationId(squareProperties.getLocationId())
                    .connectedByUserId(owners.get(0).getId())
                    .build());
            log.info("Backfilled square_connection for business {} from SQUARE_ACCESS_TOKEN/"
                    + "SQUARE_LOCATION_ID env vars.", businessId);
        };
    }
}
