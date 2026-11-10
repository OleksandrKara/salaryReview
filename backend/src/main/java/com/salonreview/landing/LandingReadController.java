package com.salonreview.landing;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.salonreview.sms.PromoConfigService;
import com.salonreview.sms.RebookingPromoSigner;
import com.salonreview.tracking.TrackingConfigService;

/**
 * Public read-only endpoints the booking sites call, at the same paths and JSON shapes the former
 * salonLandings service exposed. The business comes from the Host header.
 */
@RestController
public class LandingReadController {

    private static final Logger log = LoggerFactory.getLogger(LandingReadController.class);
    static final String CART_MENU_CACHE_CONTROL = "public, max-age=3600, stale-while-revalidate=86400";
    private static final int DEFAULT_DAYS = 30;
    private static final DateTimeFormatter ICS_TS = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final LandingBusinessResolver resolver;
    private final NailsLandingService nails;
    private final PmuLandingService pmu;
    private final ExperimentLandingService experiments;
    private final RebookingPromoSigner promoSigner;
    private final PromoConfigService promoConfig;
    private final TrackingConfigService trackingConfig;

    public LandingReadController(LandingBusinessResolver resolver, NailsLandingService nails, PmuLandingService pmu,
                                 ExperimentLandingService experiments, RebookingPromoSigner promoSigner,
                                 PromoConfigService promoConfig, TrackingConfigService trackingConfig) {
        this.resolver = resolver;
        this.nails = nails;
        this.pmu = pmu;
        this.experiments = experiments;
        this.promoSigner = promoSigner;
        this.promoConfig = promoConfig;
        this.trackingConfig = trackingConfig;
    }

    private long business(String host) {
        return resolver.resolve(host).id();
    }

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/api/services")
    public ResponseEntity<Map<String, Object>> services(@RequestHeader(value = "Host", required = false) String host) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CART_MENU_CACHE_CONTROL)
                .body(nails.cartMenu(business(host)));
    }

    @GetMapping("/api/artists")
    public List<Map<String, Object>> artists(@RequestHeader(value = "Host", required = false) String host) {
        return nails.artists(business(host));
    }

    @GetMapping("/api/availability")
    public Map<String, Object> availability(@RequestHeader(value = "Host", required = false) String host,
                                            @RequestParam String services,
                                            @RequestParam(defaultValue = "any") String artist,
                                            @RequestParam(required = false) Integer days) {
        if (days != null && (days < 1 || days > 32)) {
            throw new LandingException(422, "days must be between 1 and 32");
        }
        List<String> slugs = Arrays.stream(services.split(",")).map(LandingText::strip).filter(s -> !s.isEmpty()).toList();
        return nails.availability(business(host), slugs, artist, days == null || days == 0 ? DEFAULT_DAYS : days);
    }

    @GetMapping("/api/pmu/catalog")
    public Map<String, Object> pmuCatalog(@RequestHeader(value = "Host", required = false) String host) {
        return pmu.catalog(business(host));
    }

    @GetMapping("/api/pmu/menu")
    public Map<String, Object> pmuMenu(@RequestHeader(value = "Host", required = false) String host) {
        return pmu.menu(business(host));
    }

    @GetMapping("/api/pmu/offer")
    public Map<String, Object> pmuOffer(@RequestHeader(value = "Host", required = false) String host,
                                        @RequestParam String token) {
        return pmu.offer(business(host), token);
    }

    @GetMapping("/api/pmu/availability/technique/{slug}")
    public Map<String, Object> techniqueAvailability(@RequestHeader(value = "Host", required = false) String host,
                                                     @PathVariable String slug,
                                                     @RequestParam(required = false) Integer days) {
        return pmu.techniqueAvailability(business(host), slug, days == null || days == 0 ? DEFAULT_DAYS : days);
    }

    @GetMapping("/api/pmu/availability/consultation/{slug}")
    public Map<String, Object> consultationAvailability(@RequestHeader(value = "Host", required = false) String host,
                                                        @PathVariable String slug,
                                                        @RequestParam(required = false) Integer days) {
        return pmu.consultationAvailability(business(host), slug, days == null || days == 0 ? DEFAULT_DAYS : days);
    }

    @GetMapping("/api/pmu/availability/service/{variationId}")
    public Map<String, Object> serviceAvailability(@RequestHeader(value = "Host", required = false) String host,
                                                   @PathVariable String variationId,
                                                   @RequestParam(defaultValue = "21") int days) {
        return pmu.serviceAvailability(business(host), variationId, Math.max(1, Math.min(days, 60)));
    }

    @GetMapping("/api/experiments/{slug}")
    public Map<String, Object> experiment(@PathVariable String slug,
                                          @RequestParam(name = "variant_key", required = false) String variantKey) {
        return experiments.resolve(slug, variantKey);
    }

    @GetMapping("/api/promos/verify")
    public Map<String, Object> verifyPromo(@RequestHeader(value = "Host", required = false) String host,
                                           @RequestParam String code, @RequestParam long exp, @RequestParam String sig) {
        Map<String, Object> invalid = new LinkedHashMap<>();
        invalid.put("valid", false);
        invalid.put("discount_amount", null);
        invalid.put("min_spend", null);
        try {
            if (!promoSigner.verify(code, exp, sig) || Instant.ofEpochSecond(exp).isBefore(Instant.now())) return invalid;
            Optional<PromoConfigService.PromoTerms> terms = promoConfig.get(business(host), code);
            if (terms.isEmpty()) return invalid;
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("valid", true);
            ok.put("discount_amount", terms.get().discountCents() / 100.0);
            ok.put("min_spend", terms.get().minSpendCents() == null ? null : terms.get().minSpendCents() / 100.0);
            return ok;
        } catch (RuntimeException e) {
            log.warn("Promo verification failed", e);
            return invalid;
        }
    }

    @GetMapping("/api/tracking/clarity-config")
    public Map<String, Object> clarityConfig(@RequestHeader(value = "Host", required = false) String host) {
        String id = null;
        try {
            id = trackingConfig.clarityProjectIdFor(LandingBusinessResolver.hostOf(host));
        } catch (RuntimeException e) {
            log.warn("Clarity config lookup failed", e);
        }
        return Collections.singletonMap("clarity_project_id", id);
    }

    @GetMapping("/api/calendar/event.ics")
    public ResponseEntity<byte[]> eventIcs(@RequestParam String title, @RequestParam String start,
                                           @RequestParam(name = "duration_minutes", defaultValue = "60") int durationMinutes,
                                           @RequestParam(defaultValue = "") String details,
                                           @RequestParam(defaultValue = "") String location) {
        if (durationMinutes < 1 || durationMinutes > 24 * 60) {
            throw new LandingException(422, "duration_minutes must be between 1 and 1440");
        }
        Instant startAt;
        try {
            startAt = parseInstant(start);
        } catch (DateTimeException e) {
            throw new LandingException(422, "start must be an ISO 8601 instant");
        }
        Instant endAt = startAt.plusSeconds(durationMinutes * 60L);
        String ics = String.join("\r\n",
                "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//AKLUXNAILS//EN", "BEGIN:VEVENT",
                "UID:" + ICS_TS.format(startAt) + "@akluxnails",
                "DTSTAMP:" + ICS_TS.format(Instant.now()),
                "DTSTART:" + ICS_TS.format(startAt),
                "DTEND:" + ICS_TS.format(endAt),
                "SUMMARY:" + escapeIcs(title),
                "DESCRIPTION:" + escapeIcs(details),
                "LOCATION:" + escapeIcs(location),
                "END:VEVENT", "END:VCALENDAR", "");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/calendar; charset=utf-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"appointment.ics\"")
                .body(ics.getBytes(StandardCharsets.UTF_8));
    }

    private static Instant parseInstant(String value) {
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeException e) {
            return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
        }
    }

    static String escapeIcs(String value) {
        return value.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;")
                .replace("\r\n", "\\n").replace("\n", "\\n");
    }
}
