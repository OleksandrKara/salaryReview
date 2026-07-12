package com.salonreview.marketing;

import com.salonreview.config.MarketingLandingProperties;
import com.salonreview.web.dto.MarketingDashboardDto;
import com.salonreview.web.dto.MarketingDashboardDto.VariantStat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class MarketingDashboardService {

    private static final Logger log = LoggerFactory.getLogger(MarketingDashboardService.class);

    private final MarketingDashboardRepository repository;
    private final MarketingContactsService contactsService;
    private final MarketingLandingProperties landingProperties;

    public MarketingDashboardService(MarketingDashboardRepository repository,
                                      MarketingContactsService contactsService,
                                      MarketingLandingProperties landingProperties) {
        this.repository = repository;
        this.contactsService = contactsService;
        this.landingProperties = landingProperties;
    }

    /**
     * Never throws: any DataAccessException (e.g. the marketing schema/tables don't exist yet,
     * because the separate salonLandings service hasn't run its migrations) yields an
     * "unavailable" DTO instead of a 500 — this app's own health must never depend on that
     * other service's schema. sources is the set of {@link TrafficSourceSql} buckets to count —
     * see MarketingDashboardRepository.findVariantStats.
     */
    public MarketingDashboardDto dashboard(String slug, Set<String> sources) {
        try {
            Optional<UUID> landingPageId = repository.findLandingPageId(slug);
            if (landingPageId.isEmpty()) {
                return MarketingDashboardDto.unavailable(slug);
            }

            Instant statsSince = repository.findStatsSince(landingPageId.get()).orElse(null);
            Set<String> attributedBookingIds = repository.findAttributedBookingIds(landingPageId.get(), statsSince);
            Map<String, Long> followUpByVariant = contactsService.countFollowUpBookingsByVariant(slug, statsSince, attributedBookingIds);
            List<VariantStat> variants = repository.findVariantStats(landingPageId.get(), slug, statsSince, sources).stream()
                    .map(raw -> toVariantStat(raw, slug, followUpByVariant.getOrDefault(raw.name(), 0L)))
                    .collect(Collectors.toList());

            return new MarketingDashboardDto(true, slug, variants, statsSince == null ? null : statsSince.toString());
        } catch (DataAccessException ex) {
            log.warn("Marketing schema unavailable while building dashboard for slug={}", slug, ex);
            return MarketingDashboardDto.unavailable(slug);
        }
    }

    /** Every landing page for the dashboard's page selector; empty (not an error) if the marketing
     * schema isn't reachable — same "never throws" guarantee as dashboard().
     */
    public List<MarketingDashboardRepository.LandingPageSummary> listLandingPages() {
        try {
            return repository.listLandingPages();
        } catch (DataAccessException ex) {
            log.warn("Marketing schema unavailable while listing landing pages", ex);
            return List.of();
        }
    }

    /** Also regenerates the deep-link key from the new name, same convention as the CLI's
     * `rename` command, so the ?v=<key> link always matches the current display name.
     */
    public void renameVariant(UUID variantId, String newName) {
        if (newName == null || newName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name cannot be blank");
        }
        try {
            repository.renameVariant(variantId, newName.trim(), Slugs.slugify(newName));
        } catch (DataIntegrityViolationException ex) {
            throw keyCollisionError();
        }
    }

    public void updateVariantDescription(UUID variantId, String description) {
        repository.updateVariantDescription(variantId, description == null || description.isBlank() ? null : description.trim());
    }

    /** Blocks deletion with a friendly message when the variant has recorded page views or
     * bookings (the FK from events/attribution has no ON DELETE CASCADE — that's intentional,
     * historical data shouldn't silently disappear) rather than surfacing a raw DB error.
     */
    public void deleteVariant(UUID variantId) {
        try {
            repository.deleteVariant(variantId);
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Can't delete — this variant has recorded page views or bookings. Set its weight to 0 instead to pull it out of rotation.");
        }
    }

    /** Copies weight/content from the source variant; the new variant always starts active
     * (required for it to ever be reachable by mani/akluxnails-home's own variant picker — this
     * app no longer exposes a way to toggle that back off, see the Overview tab's variant table)
     * with a key auto-generated from its name, same convention as the CLI's `add` command.
     */
    public UUID duplicateVariant(UUID sourceVariantId, String newName) {
        if (newName == null || newName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name cannot be blank");
        }
        MarketingDashboardRepository.VariantSource source = repository.findVariantSource(sourceVariantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such variant"));
        try {
            return repository.duplicateVariant(source, newName.trim(), Slugs.slugify(newName));
        } catch (DataIntegrityViolationException ex) {
            throw keyCollisionError();
        }
    }

    /** Rename/duplicate both auto-generate a key from the name (e.g. "Control (copy)" ->
     * "control-copy") — this fires when that key already belongs to another variant, most
     * commonly from duplicating the same source twice in a row with the same default name.
     */
    private static ResponseStatusException keyCollisionError() {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "Another variant already uses the link generated from that name — try a slightly different name.");
    }

    public void updateStatsSince(String slug, Instant statsSince) {
        UUID landingPageId = repository.findLandingPageId(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such landing page"));
        repository.updateStatsSince(landingPageId, statsSince);
    }

    private VariantStat toVariantStat(MarketingDashboardRepository.RawVariantStat raw, String slug, long followUpBookings) {
        double conversionRate = raw.pageViews() == 0 ? 0.0 : (double) raw.bookingsCompleted() / raw.pageViews();
        double adjustedConversionRate = raw.pageViews() == 0
                ? 0.0
                : (double) (raw.bookingsCompleted() + followUpBookings) / raw.pageViews();
        String deepLinkUrl = raw.key() == null ? null : buildDeepLinkUrl(raw.key(), slug);
        return new VariantStat(raw.variantId(), raw.name(), raw.weight(),
                raw.pageViews(), raw.bookingsCompleted(), raw.contactsCreated(), raw.bookNowClicks(),
                conversionRate, followUpBookings, adjustedConversionRate, deepLinkUrl, raw.description());
    }

    private String buildDeepLinkUrl(String key, String slug) {
        String encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8);
        return landingProperties.baseUrlFor(slug) + "/?v=" + encodedKey;
    }
}
