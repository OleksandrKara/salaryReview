package com.salonreview.landing;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.square.SquareClient;

/** Nails (mani) appointment booking: manicure and/or pedicure plus design add-on, one Square appointment. */
@Service
public class LandingNailsBookingService {

    private static final Logger log = LoggerFactory.getLogger(LandingNailsBookingService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> CONFLICT_CODES = Set.of("CONFLICTING_APPOINTMENT", "BOOKING_TIME_NOT_AVAILABLE");

    public record Request(String startAt, String teamMemberId, List<NailsLandingService.SegmentPick> picks,
                          String givenName, String familyName, String email, String phone, boolean marketingOptIn,
                          String note, LandingTracking tracking) {
    }

    public record Confirmation(String bookingId, String status, String startAt, int durationMinutes, String serviceName,
                               String tier, String artistName, double price, Double compareAtPrice, String locationName,
                               String locationAddress, String locationPhone, String cancellationPolicyText,
                               String customerId) {
    }

    private final LandingSquareGateway square;
    private final LandingFourHandService customers;
    private final NailsLandingService nails;

    public LandingNailsBookingService(LandingSquareGateway square, LandingFourHandService customers,
                                      NailsLandingService nails) {
        this.square = square;
        this.customers = customers;
        this.nails = nails;
    }

    public Confirmation book(long businessId, Request r) {
        NailsLandingService.ResolvedBooking resolved = nails.resolveBooking(businessId, r.picks(), r.teamMemberId());

        String customerId = customers.prepareCustomer(businessId, r.givenName(), r.familyName(), r.email(), r.phone(),
                r.marketingOptIn(), r.tracking());

        List<SquareClient.LandingSegment> segments = new ArrayList<>();
        for (NailsLandingService.ResolvedSegment s : resolved.segments()) {
            segments.add(new SquareClient.LandingSegment(s.variationId(), s.variationVersion(), s.durationMinutes()));
        }
        JsonNode booking;
        try {
            booking = square.createBooking(businessId, customerId, r.startAt(), r.teamMemberId(), segments, r.note());
        } catch (RestClientResponseException e) {
            log.error("Square booking create failed: {}", e.getResponseBodyAsString());
            if (errorCodes(e).stream().anyMatch(CONFLICT_CODES::contains)) {
                throw new LandingException(409, "This time slot is no longer available.");
            }
            throw new LandingException(502, "Unable to create appointment in Square");
        } catch (RuntimeException e) {
            log.error("Square booking create failed: {}", e.toString());
            throw new LandingException(502, "Unable to create appointment in Square");
        }

        String tier = resolved.segments().stream().map(NailsLandingService.ResolvedSegment::tier)
                .filter(t -> t != null).findFirst().orElse("top");
        double total = 0;
        double compare = 0;
        int duration = 0;
        List<String> names = new ArrayList<>();
        for (NailsLandingService.ResolvedSegment s : resolved.segments()) {
            total += s.price();
            compare += s.compareAtPrice() != null ? s.compareAtPrice() : s.price();
            duration += s.durationMinutes();
            names.add(s.name());
        }

        JsonNode location = locationOrEmpty(businessId);
        String locationName = firstNonEmpty(location.path("name").asText(""), location.path("business_name").asText(""),
                "AK.LUX.NAILS");
        String phone = location.path("phone_number").asText("");
        Confirmation c = new Confirmation(booking.path("id").asText(), booking.path("status").asText(),
                booking.path("start_at").asText(r.startAt()), duration, String.join(" + ", names), tier,
                resolved.artistName(), total, compare != total ? compare : null, locationName, formatAddress(location),
                phone.isEmpty() ? null : phone, cancellationPolicy(businessId), customerId);
        log.info("Booking confirmed: id={} service={} tier={} start_at={}", c.bookingId(), c.serviceName(), c.tier(),
                c.startAt());
        return c;
    }

    private JsonNode locationOrEmpty(long businessId) {
        try {
            return square.location(businessId);
        } catch (RuntimeException e) {
            log.warn("Location lookup failed after booking was created: {}", e.getMessage());
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }
    }

    private String cancellationPolicy(long businessId) {
        try {
            String t = square.bookingProfile(businessId).path("business_appointment_settings")
                    .path("cancellation_policy_text").asText("");
            return t.isEmpty() ? null : t;
        } catch (RuntimeException e) {
            log.warn("Booking profile lookup failed after booking was created: {}", e.getMessage());
            return null;
        }
    }

    static String formatAddress(JsonNode location) {
        JsonNode a = location.path("address");
        if (a.isMissingNode() || a.isNull()) return "";
        String stateZip = (a.path("administrative_district_level_1").asText("") + " "
                + a.path("postal_code").asText("")).strip();
        List<String> parts = new ArrayList<>();
        for (String p : new String[] {a.path("address_line_1").asText(""), a.path("locality").asText(""), stateZip}) {
            if (!p.isEmpty()) parts.add(p);
        }
        return String.join(", ", parts);
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) if (v != null && !v.isEmpty()) return v;
        return "";
    }

    private static Set<String> errorCodes(RestClientResponseException e) {
        Set<String> codes = new HashSet<>();
        try {
            for (JsonNode err : JSON.readTree(e.getResponseBodyAsString()).path("errors")) {
                String c = err.path("code").asText(null);
                if (c != null) codes.add(c);
            }
        } catch (Exception ignored) {
            // transport-level or non-JSON body: no codes
        }
        return codes;
    }
}
