package com.salonreview.tracking;

import com.salonreview.domain.TrackingConfig;
import com.salonreview.repo.TrackingConfigRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Microsoft Clarity tracking-code config, one row per real hostname (see V145 — {@link
 * TrackingConfig}'s own doc explains why hostname, not business, is the key). {@link #SITE_LABELS}
 * is a small, hand-maintained registry rather than a schema column — this only ever needs to grow
 * when a new public site is deployed, at which point that same deploy already adds a migration row
 * (see V145's seed insert) right next to updating this map.
 */
@Service
public class TrackingConfigService {

    private static final Map<String, String> SITE_LABELS = Map.of(
            "akluxnails.com", "AK.LUX.NAILS — marketing site",
            "mani.akluxnails.com", "AK.LUX.NAILS — booking funnel",
            "book.pmu-annakara.com", "Anna Kara PMU — booking funnel");

    private final TrackingConfigRepository repo;

    public TrackingConfigService(TrackingConfigRepository repo) {
        this.repo = repo;
    }

    public record Site(String hostname, String siteLabel, String clarityProjectId, Instant updatedAt,
                        String updatedBy) {}

    public List<Site> list(Long businessId) {
        return repo.findAllByBusinessIdOrderByHostname(businessId).stream()
                .map(c -> new Site(c.getHostname(), labelFor(c.getHostname()), c.getClarityProjectId(),
                        c.getUpdatedAt(), c.getUpdatedBy()))
                .toList();
    }

    @Transactional
    public Site update(Long businessId, String hostname, String clarityProjectId, String updatedByUsername) {
        TrackingConfig cfg = repo.findByHostname(hostname)
                .filter(c -> c.getBusinessId().equals(businessId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no such site for this business: " + hostname));
        cfg.setClarityProjectId(blankToNull(clarityProjectId));
        cfg.setUpdatedBy(updatedByUsername);
        repo.save(cfg);
        return new Site(cfg.getHostname(), labelFor(cfg.getHostname()), cfg.getClarityProjectId(),
                cfg.getUpdatedAt(), cfg.getUpdatedBy());
    }

    /** {@code null} if this hostname has no row at all, or no id set yet — {@link
     * com.salonreview.web.InternalTrackingController} treats both the same (nothing to inject). */
    public String clarityProjectIdFor(String hostname) {
        return repo.findByHostname(hostname).map(TrackingConfig::getClarityProjectId).orElse(null);
    }

    private static String labelFor(String hostname) {
        return SITE_LABELS.getOrDefault(hostname, hostname);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
