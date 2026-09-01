package com.salonreview.square.webhook;

import com.salonreview.domain.Provider;
import com.salonreview.domain.SquareCustomerMirror;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.SquareCustomerMirrorRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.telegram.TelegramNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Alerts the business's Telegram staff channel when a NEW booking lands less than {@value
 * #SAME_DAY_THRESHOLD_HOURS} hours before its own start time — the Tara Lumley incident
 * (2026-09-01): she booked same-day and Susan, her provider, didn't notice until very late.
 * Independent listener on the same {@code booking.created} webhook event as {@link
 * SquareBookingWebhookHandler}'s mirror ingest — not entangled with it, same "separate concerns"
 * reasoning as every other listener sharing this event stream (see {@code
 * SquareWebhookController}).
 *
 * <p>Deliberately wired to {@code booking.created} only, never {@code booking.updated} — Square
 * fires {@code updated} on every edit to an already-existing booking (reschedule, note change,
 * status change, etc.), and re-alerting on those would make this noisy and cry-wolf; only a
 * booking's original creation says anything about how much notice staff actually got.
 *
 * <p>No extra Square calls: {@code startAt}/{@code createdAt} are already inline in the webhook
 * payload, the provider comes from the same {@link Provider#getSquareTeamMemberIds()} mapping
 * every other Square-team-member resolution in this codebase uses, and the customer's name comes
 * from the local {@code square_customer} mirror (see {@link SquareCustomerMirrorRepository}) —
 * already kept current independently by its own ingest/webhook path.
 */
@Service
public class SameDayBookingAlertService {

    private static final Logger log = LoggerFactory.getLogger(SameDayBookingAlertService.class);
    private static final int SAME_DAY_THRESHOLD_HOURS = 3;
    private static final Duration SAME_DAY_THRESHOLD = Duration.ofHours(SAME_DAY_THRESHOLD_HOURS);

    private final ProviderRepository providers;
    private final SquareCustomerMirrorRepository customers;
    private final TelegramNotificationService telegram;

    public SameDayBookingAlertService(ProviderRepository providers, SquareCustomerMirrorRepository customers,
                                       TelegramNotificationService telegram) {
        this.providers = providers;
        this.customers = customers;
        this.telegram = telegram;
    }

    public void handleBookingCreated(Long businessId, SquareWebhookEvent.Booking booking) {
        if (booking == null || booking.startAt() == null || booking.createdAt() == null) return;
        try {
            Instant start = Instant.parse(booking.startAt());
            Instant created = Instant.parse(booking.createdAt());
            Duration leadTime = Duration.between(created, start);
            // Negative = a backdated/rescheduled-to-the-past edge case, not a real "just booked" —
            // and >= threshold is simply not last-minute. Either way, nothing to alert.
            if (leadTime.isNegative() || leadTime.compareTo(SAME_DAY_THRESHOLD) >= 0) return;

            String providerNames = resolveProviderNames(booking);
            if (providerNames == null) return; // no known provider on this booking — nothing useful to say

            String customerName = resolveCustomerName(businessId, booking.customerId());
            telegram.sendSameDayBookingAlert(businessId, providerNames, customerName, booking.startAt(), leadTime);
        } catch (RuntimeException ex) {
            log.warn("Same-day booking alert check failed for booking {} (business {}): {}",
                    booking.id(), businessId, ex.toString());
        }
    }

    /** Comma-joined display names of every provider on this booking's segments — {@code null} if
     * none resolve to a known {@link Provider} (an unmapped/new team member, or a booking with no
     * segments at all). A no-show/blocked-time booking with no real provider isn't worth alerting
     * on either way. */
    private String resolveProviderNames(SquareWebhookEvent.Booking booking) {
        if (booking.appointmentSegments() == null) return null;
        Set<String> names = new LinkedHashSet<>();
        for (SquareClient.AppointmentSegment seg : booking.appointmentSegments()) {
            if (seg.teamMemberId() == null) continue;
            providers.findBySquareTeamMemberId(seg.teamMemberId()).map(Provider::getDisplayName).ifPresent(names::add);
        }
        return names.isEmpty() ? null : String.join(", ", names);
    }

    /** Best-effort — {@code null} if the customer isn't in our mirror yet (a genuinely new
     * customer whose {@code customer.created} webhook hasn't landed) or has no name on file. */
    private String resolveCustomerName(Long businessId, String squareCustomerId) {
        if (squareCustomerId == null) return null;
        return customers.findByBusinessIdAndSquareCustomerId(businessId, squareCustomerId)
                .map(SameDayBookingAlertService::fullName)
                .orElse(null);
    }

    private static String fullName(SquareCustomerMirror c) {
        String given = c.getGivenName();
        String family = c.getFamilyName();
        String full = ((given == null ? "" : given) + " " + (family == null ? "" : family)).trim();
        return full.isBlank() ? null : full;
    }
}
