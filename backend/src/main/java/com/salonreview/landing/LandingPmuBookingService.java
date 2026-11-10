package com.salonreview.landing;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.sms.TwilioSmsService;
import com.salonreview.telegram.ConsultationRequestNotification;
import com.salonreview.telegram.TelegramNotificationService;
import com.salonreview.web.GeoIpService;

/** PMU appointment bookings from the public site: the free consultation (no payment). */
@Service
public class LandingPmuBookingService {

    private static final Logger log = LoggerFactory.getLogger(LandingPmuBookingService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String CLIENT_FACING_NAME = "Anna Kara's PMU Studio";
    private static final Set<String> CONFLICT_CODES = Set.of("CONFLICTING_APPOINTMENT", "BOOKING_TIME_NOT_AVAILABLE");
    private static final Set<String> SOURCE_HOSTS = Set.of("pmu-annakara.com", "www.pmu-annakara.com",
            "book.pmu-annakara.com", "pmu-preview.akluxnails.com");
    private static final String PHOTO_AREAS = "the area you'd like us to work on";
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x1f\\x7f]");
    private static final Pattern SPACES = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    public record Request(String consultationSlug, String teamMemberId, String startAt, String givenName,
                          String familyName, String email, String phone, boolean marketingOptIn, String note,
                          LandingTracking tracking, String sourcePageUrl, String sourcePageTitle, String clientIp,
                          String clientCountryCode) {
    }

    public record Confirmation(String bookingId, String status, String startAt, String serviceName, String artistName,
                               String customerId) {
    }

    private final LandingSquareGateway square;
    private final LandingFourHandService customers;
    private final TelegramNotificationService telegram;
    private final TwilioSmsService sms;
    private final GeoIpService geoIp;

    public LandingPmuBookingService(LandingSquareGateway square, LandingFourHandService customers,
                                    TelegramNotificationService telegram, TwilioSmsService sms, GeoIpService geoIp) {
        this.square = square;
        this.customers = customers;
        this.telegram = telegram;
        this.sms = sms;
        this.geoIp = geoIp;
    }

    private LandingDefinitions.Consultation definition(Request r) {
        return definition(r.consultationSlug(), r.teamMemberId());
    }

    private LandingDefinitions.Consultation definition(String slug, String teamMemberId) {
        LandingDefinitions.Consultation d = LandingDefinitions.CONSULTATIONS.stream()
                .filter(c -> c.slug().equals(slug)).findFirst()
                .orElseThrow(() -> new LandingException(400, "Unknown consultation '" + slug + "'"));
        if (!d.teamMemberIds().contains(teamMemberId)) {
            throw new LandingException(400, "'" + teamMemberId + "' doesn't offer this consultation");
        }
        return d;
    }

    public Confirmation testConfirmation(long businessId, Request r) {
        LandingDefinitions.Consultation d = definition(r);
        return new Confirmation("TEST-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12), "TEST",
                r.startAt(), d.name(), artistName(businessId, r.teamMemberId()), "");
    }

    public Confirmation bookConsultation(long businessId, Request r) {
        LandingDefinitions.Consultation d = definition(r);
        JsonNode variation = variation(businessId, d.itemId(), d.variationId());
        int durationMinutes = (int) (variation.path("item_variation_data").path("service_duration").asLong(0) / 60_000);

        String customerId = customers.prepareCustomer(businessId, r.givenName(), r.familyName(), r.email(), r.phone(),
                r.marketingOptIn(), r.tracking());

        JsonNode booking;
        try {
            booking = square.createBooking(businessId, customerId, r.startAt(), r.teamMemberId(), variation.path("id").asText(),
                    variation.path("version").asLong(0), durationMinutes, r.note(), null);
        } catch (RestClientResponseException e) {
            Set<String> codes = errorCodes(e);
            log.error("Square booking create failed: {}", e.getResponseBodyAsString());
            if (codes.stream().anyMatch(CONFLICT_CODES::contains)) {
                throw new LandingException(409, "This time slot is no longer available.");
            }
            throw new LandingException(502, "Unable to create appointment in Square");
        } catch (RuntimeException e) {
            log.error("Square booking create failed: {}", e.toString());
            throw new LandingException(502, "Unable to create appointment in Square");
        }

        String startAt = booking.path("start_at").asText(r.startAt());
        String locationAddress = d.slug().startsWith("online") ? "" : address(businessId);
        String artist = artistName(businessId, r.teamMemberId());
        boolean online = d.slug().startsWith("online");
        notifyClient(businessId, r, startAt, online, locationAddress, artist);
        notifyStaff(businessId, r, startAt, online, locationAddress, artist);
        return new Confirmation(booking.path("id").asText(), booking.path("status").asText(), startAt, d.name(), artist,
                customerId);
    }

    private JsonNode variation(long businessId, String itemId, String variationId) {
        JsonNode item = square.items(businessId).get(itemId);
        if (item == null) {
            throw new LandingException(502,
                    "Square catalog item '" + itemId + "' was not found. It may have been deleted or archived.");
        }
        for (JsonNode v : item.path("item_data").path("variations")) {
            if (variationId.equals(v.path("id").asText())) return v;
        }
        throw new LandingException(400, "Variation '" + variationId + "' not found on item '" + itemId + "'");
    }

    private String artistName(long businessId, String teamMemberId) {
        try {
            JsonNode member = square.team(businessId).get(teamMemberId);
            return member == null ? null : NailsLandingService.displayName(member);
        } catch (RuntimeException e) {
            log.warn("Artist name lookup failed: {}", e.getMessage());
            return null;
        }
    }

    private String address(long businessId) {
        try {
            JsonNode a = square.location(businessId).path("address");
            if (a.isMissingNode() || a.isNull()) return "";
            String stateZip = (a.path("administrative_district_level_1").asText("") + " "
                    + a.path("postal_code").asText("")).strip();
            java.util.List<String> parts = new java.util.ArrayList<>();
            for (String p : new String[] {a.path("address_line_1").asText(""), a.path("locality").asText(""), stateZip}) {
                if (!p.isEmpty()) parts.add(p);
            }
            return String.join(", ", parts);
        } catch (RuntimeException e) {
            log.warn("Location address lookup failed: {}", e.getMessage());
            return "";
        }
    }

    private static Set<String> errorCodes(RestClientResponseException e) {
        java.util.Set<String> codes = new java.util.HashSet<>();
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

    private void notifyClient(long businessId, Request r, String startAt, boolean online, String address, String artist) {
        try {
            sms.sendTemplated(businessId, "consultation_request_confirmation", r.phone(), Map.of(
                    "name", r.givenName(), "businessName", CLIENT_FACING_NAME,
                    "detailsClause", detailsClause(startAt, online, address, artist)));
        } catch (RuntimeException e) {
            log.warn("Consultation confirmation SMS failed (booking unaffected): {}", e.getMessage());
        }
    }

    private void notifyStaff(long businessId, Request r, String startAt, boolean online, String address, String artist) {
        try {
            String campaign = r.tracking() == null ? null : cleanOneLine(r.tracking().utmCampaign(), 100);
            telegram.sendConsultationRequestAlert(businessId, new ConsultationRequestNotification(businessId, null,
                    r.givenName(), r.phone(), startAt, online, address, artist, cleanSourceUrl(r.sourcePageUrl()),
                    cleanOneLine(r.sourcePageTitle(), 120), campaign, cleanOneLine(r.note(), 500),
                    geoIp.describe(r.clientIp(), r.clientCountryCode())));
        } catch (RuntimeException e) {
            log.warn("Consultation Telegram alert failed (booking unaffected): {}", e.getMessage());
        }
    }

    static String detailsClause(String startAt, boolean online, String address, String artist) {
        String time = LandingFourHandService.formatPreferredTime(startAt);
        String who = artist == null ? "your artist" : artist;
        if (online) {
            return "It's a free online consultation by phone: " + who + " will call you at this number on " + time
                    + ", no need to come to the studio. To help " + (artist == null ? "us" : artist)
                    + " prepare, please reply with 2-3 photos of " + PHOTO_AREAS + ", taken in good daylight.";
        }
        String where = address == null || address.isEmpty() ? "" : " at " + address;
        return "It's an in-studio consultation" + where + " on " + time + ". Please arrive 5 minutes early. "
                + "To help us prepare, you're welcome to reply with 2-3 photos of " + PHOTO_AREAS + ".";
    }

    static String cleanSourceUrl(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI u = new URI(LandingText.strip(url));
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            if (!"https".equals(u.getScheme()) || !SOURCE_HOSTS.contains(host)) return null;
            String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
            return "https://" + u.getRawAuthority().toLowerCase(Locale.ROOT) + path;
        } catch (java.net.URISyntaxException e) {
            return null;
        }
    }

    static String cleanOneLine(String text, int max) {
        if (text == null || text.isEmpty()) return null;
        String line = LandingText.strip(SPACES.matcher(CONTROL.matcher(text).replaceAll(" ")).replaceAll(" "));
        if (line.isEmpty()) return null;
        if (line.codePointCount(0, line.length()) <= max) return line;
        return LandingText.strip(line.substring(0, line.offsetByCodePoints(0, max - 1))) + "…";
    }
}
