package com.salonreview.landing;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.salonreview.repo.BusinessRepository;
import com.salonreview.sms.TwilioSmsService;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import com.salonreview.telegram.FourHandRequestNotification;
import com.salonreview.telegram.TelegramNotificationService;

/**
 * The 4-hand lead: finds or creates the Square customer, stamps marketing attributes on it, and
 * alerts staff. No Square appointment is created, the salon calls back to schedule.
 */
@Service
public class LandingFourHandService {

    private static final Logger log = LoggerFactory.getLogger(LandingFourHandService.class);
    static final String SERVICE_NAME = "4-Hand Service";
    static final String CONFIRMATION_MESSAGE =
            "We've received your request and will call you shortly to confirm the exact date, time & final pricing.";
    private static final double DISPLAY_PRICE = 249.0;
    private static final String REFERENCE_ID = "mani-akluxnails-landing";
    private static final ZoneId PACIFIC = ZoneId.of("America/Los_Angeles");
    private static final DateTimeFormatter PREFERRED_TIME = DateTimeFormatter.ofPattern("EEE, MMM d 'at' h:mm a z", Locale.US);

    public record Request(String startAt, String givenName, String familyName, String email, String phone,
                          boolean marketingOptIn, String requestedServices, String note, LandingTracking clientTracking) {
    }

    public record Result(String bookingId, String customerId) {
    }

    private final SquareClientProvider squareProvider;
    private final TelegramNotificationService telegram;
    private final TwilioSmsService sms;
    private final BusinessRepository businesses;

    public LandingFourHandService(SquareClientProvider squareProvider, TelegramNotificationService telegram,
                                  TwilioSmsService sms, BusinessRepository businesses) {
        this.squareProvider = squareProvider;
        this.telegram = telegram;
        this.sms = sms;
        this.businesses = businesses;
    }

    public Result submit(long businessId, Request r) {
        SquareClient square = squareProvider.forBusiness(businessId);
        String customerId = findOrCreate(square, r);
        attachTracking(square, customerId, r.clientTracking());
        setAttribute(square, customerId, "sms_marketing_consent", r.marketingOptIn() ? "Opted In" : "Opted Out");
        if (r.email() != null) setAttribute(square, customerId, "email_marketing_consent", "Opted In");

        List<String> noteParts = new ArrayList<>();
        if (r.requestedServices() != null && !r.requestedServices().isEmpty()) {
            noteParts.add("Requested: " + r.requestedServices());
        }
        if (r.note() != null && !r.note().isEmpty()) noteParts.add(r.note());
        String note = noteParts.isEmpty() ? null : String.join(" — ", noteParts);

        String bookingId = "four-hand-req-" + UUID.randomUUID();
        notifyStaff(r, note);
        return new Result(bookingId, customerId);
    }

    private String findOrCreate(SquareClient square, Request r) {
        String existing;
        try {
            existing = searchByPhoneThenEmail(square, r.phone(), r.email());
        } catch (RuntimeException e) {
            log.error("Square customer search failed: {}", e.getMessage());
            throw new LandingException(502, "Unable to look up customer record in Square");
        }
        if (existing != null) return existing;
        try {
            return square.createLandingCustomer(r.givenName(), r.familyName(), r.phone(), r.email(), REFERENCE_ID).id();
        } catch (RuntimeException e) {
            log.error("Square customer create failed: {}", e.getMessage());
            throw new LandingException(502, "Unable to create customer record in Square");
        }
    }

    /** Phone first; the email match is only trusted when its own phone is blank or the same. */
    static String searchByPhoneThenEmail(SquareClient square, String phone, String email) {
        String e164 = phone == null || phone.isEmpty() ? null : LandingPhones.e164(phone);
        if (e164 != null) {
            SquareClient.Customer match = square.firstCustomerExact("phone_number", e164);
            if (match != null) return match.id();
        }
        if (email != null && !email.isEmpty()) {
            SquareClient.Customer match = square.firstCustomerExact("email_address", email);
            if (match != null && (e164 == null || match.phoneNumber() == null || match.phoneNumber().equals(e164))) {
                return match.id();
            }
        }
        return null;
    }

    /** Read-only lookup for a lead that has not booked yet; never creates, never throws. */
    public String findExistingCustomerId(long businessId, String phone, String email) {
        try {
            return searchByPhoneThenEmail(squareProvider.forBusiness(businessId), phone, email);
        } catch (RuntimeException e) {
            log.warn("Square customer lookup failed, treating as not found: {}", e.getMessage());
            return null;
        }
    }

    private void attachTracking(SquareClient square, String customerId, LandingTracking t) {
        setAttribute(square, customerId, "marketing_traffic_source", LandingTracking.classify(t));
        if (t != null) {
            if (t.utmSource() != null) setAttribute(square, customerId, "utm_source", t.utmSource());
            if (t.utmMedium() != null) setAttribute(square, customerId, "utm_medium", t.utmMedium());
            if (t.utmCampaign() != null) setAttribute(square, customerId, "utm_campaign", t.utmCampaign());
        }
        String existing;
        try {
            existing = square.customerAttribute(customerId, "original_organic_source");
        } catch (RuntimeException e) {
            log.error("Failed to check existing 'original_organic_source' for customer {}: {}", customerId, e.getMessage());
            return;
        }
        if (existing != null) return;
        setAttribute(square, customerId, "original_organic_source", t != null ? LandingTracking.classify(t) : "na");
    }

    private void setAttribute(SquareClient square, String customerId, String key, String value) {
        try {
            square.setCustomerAttribute(customerId, key, value);
        } catch (RuntimeException e) {
            log.error("Failed to set Square customer custom attribute '{}' for customer {}: {}", key, customerId, e.getMessage());
        }
    }

    private void notifyStaff(Request r, String note) {
        Long legacy = businesses.legacySmsBusiness().getId();
        try {
            telegram.sendFourHandRequestAlert(legacy, new FourHandRequestNotification("mani",
                    (r.givenName() + " " + r.familyName()).strip(), r.phone(), r.requestedServices(), r.startAt(), note,
                    DISPLAY_PRICE, null, null));
        } catch (RuntimeException e) {
            log.warn("4-hand Telegram alert failed (lead capture unaffected): {}", e.getMessage());
        }
        try {
            sms.sendTemplated(legacy, "four_hand_request_received", r.phone(),
                    Map.of("name", r.givenName(), "preferredTime", formatPreferredTime(r.startAt())));
        } catch (RuntimeException e) {
            log.warn("4-hand SMS failed (lead capture unaffected): {}", e.getMessage());
        }
    }

    /** Instant for an ISO-8601 string; a missing offset is read as UTC. Null when it cannot be parsed. */
    static Instant parseInstant(String iso) {
        if (iso == null) return null;
        String s = iso.replace("Z", "+00:00");
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeException e) {
            try {
                return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC);
            } catch (DateTimeException e2) {
                return null;
            }
        }
    }

    static String formatPreferredTime(String iso) {
        Instant at = parseInstant(iso);
        return at == null ? iso : PREFERRED_TIME.format(at.atZone(PACIFIC));
    }
}
