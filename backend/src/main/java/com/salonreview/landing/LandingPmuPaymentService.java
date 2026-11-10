package com.salonreview.landing;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.sms.ConsultationOfferService;
import com.salonreview.telegram.PaymentFailedNotification;
import com.salonreview.telegram.TelegramNotificationService;

/**
 * PMU bookings that take a card deposit: the procedure picked from the live menu and the legacy
 * fixed-technique deposit. The slot is reserved first and the card charged second, so a declined card
 * only ever needs the reservation cancelled; money is never taken for a slot that was not booked.
 */
@Service
public class LandingPmuPaymentService {

    private static final Logger log = LoggerFactory.getLogger(LandingPmuPaymentService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> CONFLICT_CODES = Set.of("CONFLICTING_APPOINTMENT", "BOOKING_TIME_NOT_AVAILABLE");
    private static final Set<String> DECLINE_CODES = Set.of("CARD_DECLINED", "CVV_FAILURE",
            "ADDRESS_VERIFICATION_FAILURE", "INVALID_EXPIRATION", "INSUFFICIENT_FUNDS", "CARD_EXPIRED");
    static final long LEGACY_DEPOSIT_CENTS = 10_000;
    static final String DECLINED_MESSAGE = "Your card was declined. Please try a different card.";
    static final String PAYMENT_FAILED_MESSAGE = "Unable to process payment";

    public record Customer(String givenName, String familyName, String email, String phone, boolean marketingOptIn) {
    }

    public record ServiceRequest(String variationId, String teamMemberId, String startAt, Customer customer,
                                 String sourceId, String note, LandingTracking tracking, String offerToken,
                                 boolean depositPolicyAccepted) {
    }

    public record DepositRequest(String techniqueSlug, String teamMemberId, String startAt, Customer customer,
                                 String sourceId, String note, LandingTracking tracking) {
    }

    public record Result(String bookingId, String status, String startAt, int durationMinutes, String serviceName,
                         double fullPrice, double depositAmount, double remainingBalance, String artistName,
                         String paymentId, String customerId, double offerDiscount) {
        Result withOfferDiscount(double discount) {
            return new Result(bookingId, status, startAt, durationMinutes, serviceName, fullPrice, depositAmount,
                    remainingBalance, artistName, paymentId, customerId, discount);
        }
    }

    private final LandingSquareGateway square;
    private final PmuLandingService menu;
    private final LandingFourHandService customers;
    private final TelegramNotificationService telegram;
    private final ConsultationOfferService offers;

    public LandingPmuPaymentService(LandingSquareGateway square, PmuLandingService menu,
                                    LandingFourHandService customers, TelegramNotificationService telegram,
                                    ConsultationOfferService offers) {
        this.square = square;
        this.menu = menu;
        this.customers = customers;
        this.telegram = telegram;
        this.offers = offers;
    }

    // ---- procedure from the live menu ----

    public Result bookService(long businessId, ServiceRequest r, boolean test) {
        PmuLandingService.FoundOption option;
        try {
            option = menu.findOption(businessId, r.variationId()).orElse(null);
        } catch (LandingException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("PMU menu lookup failed: {}", e.toString());
            throw new LandingException(502, "Unable to load the menu from Square");
        }
        if (option == null) throw new LandingException(400, "Unknown service option '" + r.variationId() + "'");
        if (!option.teamMemberIds().contains(r.teamMemberId())) {
            throw new LandingException(400, "'" + r.teamMemberId() + "' doesn't offer this service");
        }
        double deposit = option.depositAmount();
        boolean noCard = r.sourceId() == null || r.sourceId().isEmpty();
        if (deposit > 0 && noCard && !test) {
            throw new LandingException(400, "A card is required for this service's deposit");
        }
        if (deposit > 0 && !r.depositPolicyAccepted()) {
            throw new LandingException(400, "Please agree to the deposit and cancellation policy to book");
        }
        String artist = artistName(businessId, r.teamMemberId());
        double remaining = option.price() - deposit;
        if (test) {
            return new Result("TEST-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12), "TEST",
                    r.startAt(), option.durationMinutes(), option.serviceName(), option.price(), deposit, remaining,
                    artist, null, "", 0);
        }

        Customer c = r.customer();
        String customerId = customers.prepareCustomer(businessId, c.givenName(), c.familyName(), c.email(), c.phone(),
                c.marketingOptIn(), r.tracking());
        JsonNode booking = reserve(businessId, customerId, r.startAt(), r.teamMemberId(), option.variationId(),
                option.variationVersion(), option.durationMinutes(), r.note(),
                deposit > 0 ? depositPolicyNote(deposit) : null);
        String bookingId = booking.path("id").asText();
        String paymentId = null;
        if (deposit > 0) {
            paymentId = charge(businessId, c, option.serviceName(), bookingId, customerId, r.sourceId(),
                    Math.round(deposit * 100),
                    "Deposit for " + option.serviceName() + " (booking " + bookingId
                            + "); cancellation policy accepted online");
        }
        return new Result(bookingId, booking.path("status").asText(), booking.path("start_at").asText(r.startAt()),
                option.durationMinutes(), option.serviceName(), option.price(), deposit, remaining, artist, paymentId,
                customerId, 0);
    }

    /** The $ off from a personal consultation-offer link, 0 when none applies. Never throws. */
    public double offerDiscount(long businessId, String token, double fullPrice, boolean test, String customerId,
                                String startAt) {
        if (token == null || token.isEmpty()) return 0;
        try {
            ConsultationOfferService.Offer offer = offers.check(businessId, token);
            if (!offer.valid() || offer.discountCents() <= 0 || fullPrice < offer.minSpendCents() / 100.0) return 0;
            boolean applied = test || offers.claim(businessId, token, customerId,
                    LandingFourHandService.parseInstant(startAt));
            return applied ? offer.discountCents() / 100.0 : 0;
        } catch (RuntimeException e) {
            log.warn("Consultation offer handling failed (booking unaffected): {}", e.toString());
            return 0;
        }
    }

    // ---- legacy fixed-technique deposit ----

    public Result bookDeposit(long businessId, DepositRequest r) {
        LandingDefinitions.Technique t = LandingDefinitions.TECHNIQUES.stream()
                .filter(x -> x.slug().equals(r.techniqueSlug())).findFirst().orElse(null);
        if (t == null) throw new LandingException(400, "Unknown technique '" + r.techniqueSlug() + "'");
        if (!t.teamMemberIds().contains(r.teamMemberId())) {
            throw new LandingException(400, "'" + r.teamMemberId() + "' doesn't offer this technique");
        }
        JsonNode item = square.items(businessId).get(t.itemId());
        if (item == null) {
            throw new LandingException(502,
                    "Square catalog item '" + t.itemId() + "' was not found. It may have been deleted or archived.");
        }
        JsonNode variation = null;
        for (JsonNode v : item.path("item_data").path("variations")) {
            if (t.variationId().equals(v.path("id").asText())) variation = v;
        }
        if (variation == null) {
            throw new LandingException(400, "Variation '" + t.variationId() + "' not found on item '" + t.itemId() + "'");
        }
        JsonNode vd = variation.path("item_variation_data");
        double fullPrice = vd.path("price_money").path("amount").asLong(0) / 100.0;
        int durationMinutes = (int) (vd.path("service_duration").asLong(0) / 60_000);

        Customer c = r.customer();
        String customerId = customers.prepareCustomer(businessId, c.givenName(), c.familyName(), c.email(), c.phone(),
                c.marketingOptIn(), r.tracking());
        JsonNode booking = reserve(businessId, customerId, r.startAt(), r.teamMemberId(), variation.path("id").asText(),
                variation.path("version").asLong(0), durationMinutes, r.note(), null);
        String bookingId = booking.path("id").asText();
        String paymentId = charge(businessId, c, t.name(), bookingId, customerId, r.sourceId(), LEGACY_DEPOSIT_CENTS,
                "Deposit for " + t.name() + " (booking " + bookingId + ")");
        double deposit = LEGACY_DEPOSIT_CENTS / 100.0;
        return new Result(bookingId, booking.path("status").asText(), booking.path("start_at").asText(r.startAt()),
                durationMinutes, t.name(), fullPrice, deposit, fullPrice - deposit,
                artistName(businessId, r.teamMemberId()), paymentId, customerId, 0);
    }

    // ---- shared steps ----

    private JsonNode reserve(long businessId, String customerId, String startAt, String teamMemberId,
                             String variationId, long version, int durationMinutes, String note, String sellerNote) {
        try {
            return square.createBooking(businessId, customerId, startAt, teamMemberId, variationId, version,
                    durationMinutes, note, sellerNote);
        } catch (RestClientResponseException e) {
            log.error("Square booking create failed: {}", e.getResponseBodyAsString());
            if (errorCodes(e).stream().anyMatch(CONFLICT_CODES::contains)) {
                throw new LandingException(409, "This time slot is no longer available.");
            }
            throw new LandingException(502, "Unable to create appointment in Square", false,
                    String.join(", ", errorCodesInOrder(e)));
        } catch (RuntimeException e) {
            log.error("Square booking create failed: {}", e.toString());
            throw new LandingException(502, "Unable to create appointment in Square");
        }
    }

    /** Charges the card; on any failure cancels the reservation, alerts staff and throws. */
    private String charge(long businessId, Customer c, String serviceName, String bookingId, String customerId,
                          String sourceId, long amountCents, String note) {
        // One key per charge: a retry after a lost response returns the original payment instead of a second one.
        String key = UUID.randomUUID().toString();
        try {
            JsonNode payment;
            try {
                payment = square.createPayment(businessId, key, sourceId, amountCents, customerId, note);
            } catch (ResourceAccessException e) {
                log.warn("Square payment call got no response, retrying once with the same idempotency key: {}",
                        e.getMessage());
                payment = square.createPayment(businessId, key, sourceId, amountCents, customerId, note);
            }
            return payment.path("id").asText();
        } catch (RuntimeException e) {
            Set<String> codes = e instanceof RestClientResponseException re ? errorCodesInOrder(re) : Set.of();
            log.error("Square payment create failed: {}",
                    e instanceof RestClientResponseException re ? re.getResponseBodyAsString() : e.toString());
            boolean declined = codes.stream().anyMatch(DECLINE_CODES::contains);
            String message = declined ? DECLINED_MESSAGE : PAYMENT_FAILED_MESSAGE;
            try {
                square.cancelBooking(businessId, bookingId);
            } catch (RuntimeException ce) {
                log.error("Failed to cancel booking {} after a deposit charge failure: {}", bookingId, ce.toString());
            }
            try {
                String name = c.givenName() + " " + c.familyName();
                telegram.sendPaymentFailedAlert(businessId, new PaymentFailedNotification(businessId, null, name,
                        c.phone(), serviceName, amountCents / 100.0, message,
                        codes.isEmpty() ? null : String.join(", ", codes), declined));
            } catch (RuntimeException ne) {
                log.warn("Payment-failed alert failed: {}", ne.toString());
            }
            throw new LandingException(declined ? 402 : 502, message, true, null);
        }
    }

    static String depositPolicyNote(double deposit) {
        String when = java.time.ZonedDateTime.now(java.time.ZoneId.of("America/Los_Angeles"))
                .format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, yyyy hh:mm a", java.util.Locale.US))
                + " PT";
        String amount = String.format(java.util.Locale.US, "$%,.0f", deposit);
        return "Deposit policy accepted online " + when + ": the " + amount + " deposit goes toward the procedure; "
                + "24 hours' notice to cancel or reschedule; the client authorized the studio to keep the deposit "
                + "as a cancellation fee for a same-day cancellation or reschedule, or a no-show.";
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

    private static Set<String> errorCodes(RestClientResponseException e) {
        return new HashSet<>(errorCodesInOrder(e));
    }

    private static java.util.LinkedHashSet<String> errorCodesInOrder(RestClientResponseException e) {
        java.util.LinkedHashSet<String> codes = new java.util.LinkedHashSet<>();
        try {
            for (JsonNode err : JSON.readTree(e.getResponseBodyAsString()).path("errors")) {
                String c = err.path("code").asText(null);
                if (c != null) codes.add(c);
            }
        } catch (Exception ignored) {
            // non-JSON body: no codes
        }
        return codes;
    }
}
