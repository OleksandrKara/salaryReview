package com.salonreview.marketing;

import com.salonreview.config.MarketingLandingProperties;
import com.salonreview.web.dto.MarketingDashboardDto;
import com.salonreview.web.dto.MarketingDashboardDto.VariantStat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class MarketingDashboardService {

    private static final Logger log = LoggerFactory.getLogger(MarketingDashboardService.class);

    private final MarketingDashboardRepository repository;
    private final MarketingLandingProperties landingProperties;

    public MarketingDashboardService(MarketingDashboardRepository repository, MarketingLandingProperties landingProperties) {
        this.repository = repository;
        this.landingProperties = landingProperties;
    }

    /**
     * Never throws: any DataAccessException (e.g. the marketing schema/tables don't exist yet,
     * because the separate salonLandings service hasn't run its migrations) yields an
     * "unavailable" DTO instead of a 500 — this app's own health must never depend on that
     * other service's schema.
     */
    public MarketingDashboardDto dashboard(String slug) {
        try {
            Optional<UUID> landingPageId = repository.findLandingPageId(slug);
            if (landingPageId.isEmpty()) {
                return MarketingDashboardDto.unavailable(slug);
            }

            String experimentStatus = repository.findExperimentStatus(landingPageId.get()).orElse("none");
            List<VariantStat> variants = repository.findVariantStats(landingPageId.get()).stream()
                    .map(this::toVariantStat)
                    .collect(Collectors.toList());

            return new MarketingDashboardDto(true, slug, experimentStatus, variants);
        } catch (DataAccessException ex) {
            log.warn("Marketing schema unavailable while building dashboard for slug={}", slug, ex);
            return MarketingDashboardDto.unavailable(slug);
        }
    }

    private VariantStat toVariantStat(MarketingDashboardRepository.RawVariantStat raw) {
        double conversionRate = raw.pageViews() == 0 ? 0.0 : (double) raw.bookingsCompleted() / raw.pageViews();
        String deepLinkUrl = raw.key() == null ? null : buildDeepLinkUrl(raw.key());
        return new VariantStat(raw.variantId(), raw.name(), raw.weight(), raw.active(),
                raw.pageViews(), raw.bookingsCompleted(), conversionRate, deepLinkUrl);
    }

    private String buildDeepLinkUrl(String key) {
        String encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8);
        return landingProperties.getLandingBaseUrl() + "/?v=" + encodedKey;
    }
}
