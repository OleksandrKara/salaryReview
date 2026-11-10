package com.salonreview.landing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Public write endpoints the booking sites call (visit/event/funnel tracking, step-1 contact capture,
 * 4-hand request), at the same paths and JSON shapes the former salonLandings service served. Off
 * unless landing.writes.enabled is set, so merging this changes nothing until nginx routes to it.
 */
@RestController
public class LandingWriteController {

    private static final Logger log = LoggerFactory.getLogger(LandingWriteController.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final java.util.Set<String> EVENT_TYPES =
            java.util.Set.of("page_view", "click", "booking_started", "booking_completed");
    static final String SMS_CONSENT_VERSION = "booking_form_v1";
    static final String SMS_CONSENT_TEXT = "Text me reminders & exclusive offers";
    static final String EMAIL_CONSENT_VERSION = "booking_form_v1";
    static final String EMAIL_CONSENT_TEXT = "Implied consent: customer provided their email address to complete a booking; "
            + "no separate opt-in checkbox shown. Independent of the SMS marketing choice.";

    private final boolean enabled;
    private final LandingBusinessResolver resolver;
    private final LandingWriteRepository repository;
    private final LandingAbuseGuard abuseGuard;
    private final LandingFourHandService fourHand;

    public LandingWriteController(@Value("${landing.writes.enabled:false}") boolean enabled,
                                  LandingBusinessResolver resolver, LandingWriteRepository repository,
                                  LandingAbuseGuard abuseGuard, LandingFourHandService fourHand) {
        this.enabled = enabled;
        this.resolver = resolver;
        this.repository = repository;
        this.abuseGuard = abuseGuard;
        this.fourHand = fourHand;
    }

    private static ResponseEntity<Map<String, Object>> created(Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    private void requireEnabled() {
        if (!enabled) throw new LandingException(404, "Not Found");
    }

    private static JsonNode parse(String raw) {
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            throw LandingWriteAdvice.badJson();
        }
    }

    private long business(HttpServletRequest request) {
        return resolver.resolve(request.getHeader("Host")).id();
    }

    @PostMapping("/api/tracking/visit")
    public ResponseEntity<Map<String, Object>> visit(@RequestBody(required = false) String raw,
                                                     HttpServletRequest request, HttpServletResponse response) {
        requireEnabled();
        long businessId = business(request);
        LandingTracking snapshot = LandingBody.root(parse(raw)).snapshotFinished();
        LandingTracking resolved = resolveSnapshot(request, response, snapshot);
        LandingUserAgents.Context ctx = clientContext(request);
        if (!LandingUserAgents.isBot(request.getHeader("User-Agent"))) {
            try {
                repository.insertVisit(businessId, resolved, ctx);
            } catch (RuntimeException e) {
                log.error("Failed to record visit", e);
            }
        }
        return created(Map.of("visitor_id", resolved.visitorId()));
    }

    @PostMapping("/api/tracking/event")
    public ResponseEntity<Map<String, Object>> event(@RequestBody(required = false) String raw,
                                                     HttpServletRequest request, HttpServletResponse response) {
        requireEnabled();
        long businessId = business(request);
        LandingBody body = LandingBody.root(parse(raw));
        String session = body.string("session_id", true, 0, 64);
        String lpid = body.string("landing_page_id", 0);
        String variant = body.string("variant_id", 0);
        String type = body.string("event_type", true, 0, 0);
        Map<String, Object> metadata = body.object("metadata");
        if (type != null && !EVENT_TYPES.contains(type)) {
            body.rejectLiteral("event_type", type, "'page_view', 'click', 'booking_started' or 'booking_completed'");
        }
        body.finish();
        Map<String, String> cookies = LandingIdentity.parseCookies(request);
        String vid = LandingIdentity.resolveVisitorId(cookies, response, session);
        LandingIdentity.Assignment a = LandingIdentity.resolveVariant(cookies, response, lpid, variant);
        if (!LandingUserAgents.isBot(request.getHeader("User-Agent"))) {
            try {
                repository.insertEvent(businessId, vid, a.landingPageId(), a.variantId(), type, JSON.writeValueAsString(metadata));
            } catch (Exception e) {
                log.error("Failed to record tracking event (event_type={})", type, e);
            }
        }
        return created(Map.of("recorded", true));
    }

    @PostMapping("/api/tracking/funnel-event")
    public ResponseEntity<Map<String, Object>> funnelEvent(@RequestBody(required = false) String raw,
                                                           HttpServletRequest request, HttpServletResponse response) {
        requireEnabled();
        long businessId = business(request);
        LandingBody body = LandingBody.root(parse(raw));
        String session = body.string("session_id", true, 0, 64);
        String lpid = body.string("landing_page_id", 0);
        String variant = body.string("variant_id", 0);
        String flowKey = body.string("flow_key", true, 0, 64);
        String stepKey = body.string("step_key", true, 0, 64);
        Integer stepIndex = body.integer("step_index", true, 0);
        Integer total = body.integer("step_count_total", true, 1);
        Map<String, Object> metadata = body.object("metadata");
        body.finish();
        Map<String, String> cookies = LandingIdentity.parseCookies(request);
        String vid = LandingIdentity.resolveVisitorId(cookies, response, session);
        LandingIdentity.Assignment a = LandingIdentity.resolveVariant(cookies, response, lpid, variant);
        if (!LandingUserAgents.isBot(request.getHeader("User-Agent"))) {
            try {
                repository.insertFunnelEvent(businessId, vid, a.landingPageId(), a.variantId(), flowKey, stepKey,
                        stepIndex, total, JSON.writeValueAsString(metadata));
            } catch (Exception e) {
                log.error("Failed to record booking funnel step (flow_key={}, step_key={})", flowKey, stepKey, e);
            }
        }
        return created(Map.of("recorded", true));
    }

    @PostMapping("/api/contacts")
    public ResponseEntity<Map<String, Object>> contact(@RequestBody(required = false) String raw,
                                                       HttpServletRequest request, HttpServletResponse response) {
        requireEnabled();
        long businessId = business(request);
        LandingBody body = LandingBody.root(parse(raw));
        String givenName = body.string("given_name", true, 1, 100);
        String phoneRaw = body.string("phone_number", true, 7, 20);
        String email = body.email("email_address");
        LandingTracking snapshot = body.tracking("tracking");
        body.finish();

        LandingTracking tracking = snapshot == null ? null : resolveSnapshot(request, response, snapshot);
        String phone = LandingPhones.forStorage(phoneRaw);
        String squareCustomerId = fourHand.findExistingCustomerId(businessId, phone, email);
        LandingUserAgents.Context ctx = clientContext(request);
        try {
            String[] landing = repository.resolveLandingContext(tracking == null ? null : tracking.landingPageId(),
                    tracking == null ? null : tracking.variantId());
            String source = LandingTracking.classify(tracking);
            repository.upsertContactStep1(businessId, phone, givenName, email, source,
                    tracking == null ? null : tracking.utmSource(), tracking == null ? null : tracking.utmMedium(),
                    tracking == null ? null : tracking.utmCampaign(), tracking == null ? null : tracking.referrer(),
                    landing[0], landing[1], ctx, squareCustomerId);
            repository.insertSubmission(businessId, tracking == null ? null : tracking.visitorId(), "step1", tracking,
                    ctx, null, null, null, email, phone, landing[0], landing[1], source);
        } catch (RuntimeException e) {
            log.error("Failed to record Step 1 contact", e);
        }
        return created(Map.of("recorded", true));
    }

    @PostMapping("/api/bookings/four-hand-request")
    public ResponseEntity<Map<String, Object>> fourHandRequest(@RequestBody(required = false) String raw,
                                                               HttpServletRequest request, HttpServletResponse response) {
        requireEnabled();
        long businessId = business(request);
        LandingBody body = LandingBody.root(parse(raw));
        LandingBody slot = body.child("slot", true);
        String startAt = null;
        if (slot != null) {
            startAt = slot.string("start_at", true, 0, 0);
            slot.string("team_member_id", true, 0, 0);
            java.util.List<LandingBody> segments = slot.children("segments", true);
            if (segments != null) {
                for (LandingBody s : segments) {
                    s.string("service_slug", true, 0, 0);
                    s.string("service_variation_id", true, 0, 0);
                    s.integer("service_variation_version", true, null);
                }
            }
        }
        LandingBody customer = body.child("customer", true);
        String given = null;
        String family = null;
        String email = null;
        String phoneRaw = null;
        boolean optIn = false;
        if (customer != null) {
            given = customer.string("given_name", true, 1, 100);
            family = customer.string("family_name", true, 1, 100);
            email = customer.email("email_address");
            phoneRaw = customer.string("phone_number", true, 7, 20);
            optIn = customer.bool("marketing_opt_in", false);
        }
        String requested = body.string("requested_services", 200);
        String note = body.string("note", 500);
        LandingTracking snapshot = body.tracking("tracking");
        String website = body.string("website", 0);
        body.string("form_rendered_at", 0);
        body.integer("form_filled_ms", false, null);
        String token = body.string("turnstile_token", 0);
        body.finish();

        LandingTracking tracking = snapshot == null ? null : resolveSnapshot(request, response, snapshot);
        LandingUserAgents.Context ctx = clientContext(request);
        String phone = LandingPhones.forStorage(phoneRaw);
        abuseGuard.check(businessId, "four_hand_request", phone, ctx.ipAddress(), website, token);

        LandingFourHandService.Result result = fourHand.submit(businessId, new LandingFourHandService.Request(
                startAt, given, family, email, phoneRaw, optIn, requested, note, snapshot));
        String bookingId = result.bookingId();

        try {
            String[] landing = repository.resolveLandingContext(tracking == null ? null : tracking.landingPageId(),
                    tracking == null ? null : tracking.variantId());
            repository.insertSubmission(businessId, tracking == null ? null : tracking.visitorId(), "four_hand_request",
                    tracking, ctx, bookingId, LandingFourHandService.SERVICE_NAME, null, email, phone, landing[0],
                    landing[1], LandingTracking.classify(tracking));
        } catch (RuntimeException e) {
            log.error("Failed to record marketing submission (booking itself was unaffected)", e);
        }
        if (tracking != null && tracking.landingPageId() != null && tracking.variantId() != null) {
            try {
                repository.insertAttribution(businessId, bookingId, tracking.landingPageId(), tracking.variantId(),
                        tracking.utmSource());
            } catch (RuntimeException e) {
                log.error("Failed to record attribution (booking itself was unaffected)", e);
            }
        }
        String visitor = tracking == null ? null : tracking.visitorId();
        try {
            repository.insertSmsConsent(businessId, phone, optIn, SMS_CONSENT_TEXT, SMS_CONSENT_VERSION,
                    "four_hand_request", visitor, ctx.ipAddress());
        } catch (RuntimeException e) {
            log.error("Failed to record SMS consent (booking itself was unaffected)", e);
        }
        if (email != null) {
            try {
                repository.insertEmailConsent(businessId, email, true, EMAIL_CONSENT_TEXT, EMAIL_CONSENT_VERSION,
                        "four_hand_request", visitor, ctx.ipAddress());
            } catch (RuntimeException e) {
                log.error("Failed to record email consent (booking itself was unaffected)", e);
            }
        }
        try {
            String[] landing = repository.resolveLandingContext(tracking == null ? null : tracking.landingPageId(),
                    tracking == null ? null : tracking.variantId());
            Instant start = LandingFourHandService.parseInstant(startAt);
            repository.updateContactAfterBooking(businessId, phone, given, email, LandingTracking.classify(tracking),
                    tracking == null ? null : tracking.utmSource(), tracking == null ? null : tracking.utmMedium(),
                    tracking == null ? null : tracking.utmCampaign(), tracking == null ? null : tracking.referrer(),
                    landing[0], landing[1], ctx, optIn, email != null, result.customerId(), bookingId, "requested",
                    start, LandingFourHandService.SERVICE_NAME, (BigDecimal) null, null);
        } catch (RuntimeException e) {
            log.error("Failed to link contact to booking (booking itself was unaffected)", e);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("booking_id", bookingId);
        out.put("status", "requested");
        out.put("service_name", LandingFourHandService.SERVICE_NAME);
        out.put("message", LandingFourHandService.CONFIRMATION_MESSAGE);
        return created(out);
    }

    private static LandingUserAgents.Context clientContext(HttpServletRequest request) {
        return LandingUserAgents.derive(request.getHeader("User-Agent"), request.getHeader("X-Forwarded-For"),
                request.getRemoteAddr());
    }

    private static LandingTracking resolveSnapshot(HttpServletRequest request, HttpServletResponse response,
                                                   LandingTracking snapshot) {
        Map<String, String> cookies = LandingIdentity.parseCookies(request);
        String vid = LandingIdentity.resolveVisitorId(cookies, response, snapshot.visitorId());
        LandingIdentity.Assignment a = LandingIdentity.resolveVariant(cookies, response, snapshot.landingPageId(),
                snapshot.variantId());
        return snapshot.withIdentity(vid, a.landingPageId(), a.variantId());
    }
}
