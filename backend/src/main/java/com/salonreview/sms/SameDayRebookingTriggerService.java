package com.salonreview.sms;

import com.salonreview.domain.SameDayRebookingSend;
import com.salonreview.repo.SameDayRebookingSendRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Turns the same qualifying Square {@code payment.updated} event
 * {@code CheckoutReviewTriggerService} already enqueues off of into a pending
 * {@code same_day_rebooking_discount} send — see openspec/changes/same-day-rebooking-discount
 * design.md D1/D2. Called directly from {@code CheckoutReviewTriggerService.handlePaymentUpdated}
 * with already-resolved values (no duplicate Square lookups). Never throws back to the webhook
 * controller — matches the "never block, never throw" convention every notifier in this codebase
 * follows.
 *
 * <p>{@code businessId} is passed in by the caller, which today always resolves it via
 * {@code BusinessRepository#legacySmsBusiness} (the webhook handler is still Phase-3.6-blocked —
 * see tasks.md 3.7) — this service itself is business-id-correct regardless of how the caller got
 * there, so it needs no changes once that upstream gap closes.
 */
@Service
public class SameDayRebookingTriggerService {

    private static final Logger log = LoggerFactory.getLogger(SameDayRebookingTriggerService.class);
    /**
     * Send timing (owner decision 2026-10-01, after a 60-day look at this automation's own data):
     * <ul>
     *   <li>1 hour after checkout (was 3). Nearly every in-salon rebooking happens within 15 min of
     *       paying (138 of 197 within 5 min) and almost nobody rebooks on their own between 30 min
     *       and 3 h (2 of 197), so waiting longer only made the text land later; by 1 h, ~83% of
     *       the clients who answer the 1-to-5 rating text have already answered it, so the two
     *       texts don't pile up.</li>
     *   <li>Never after 20:45 salon time. Nudges sent after 21:00 converted ~2.5x worse (5.7% vs
     *       14.5% booked within a week), and the consented version is a marketing text.
     *       A late checkout whose +1 h would pass 20:45 is sent at 20:45 instead, as long as that
     *       is still {@link #MIN_LEAD} after paying; otherwise it moves to 10:00 the next morning.</li>
     *   <li>Never before {@link #EARLIEST_SAME_DAY}.</li>
     *   <li>The promo runs to midnight of the day the email follow-up goes out
     *       ({@link WinbackEmailFallbackScheduler}): a text before 19:00 gets its email at 19:00 the
     *       same day (offer to that midnight); a 19:00-20:45 text gets its email at 10:00 the next
     *       morning, and a next-morning text at 19:00 that day (offer to that next midnight). So the
     *       email's "expires tonight" is always true.</li>
     * </ul>
     */
    static final Duration SEND_DELAY = Duration.ofHours(1);
    static final Duration MIN_LEAD = Duration.ofMinutes(15);
    static final LocalTime EARLIEST_SAME_DAY = LocalTime.of(9, 0);
    static final LocalTime LATEST_SEND = LocalTime.of(20, 45);
    static final LocalTime NEXT_DAY_SEND = LocalTime.of(10, 0);
    static final LocalTime EMAIL_FOLLOW_UP = LocalTime.of(19, 0);
    /** DST-safe — always resolves to the salon's real local midnight, not a fixed UTC offset. */
    static final ZoneId SALON_ZONE = ZoneId.of("America/Los_Angeles");

    record Schedule(Instant sendDueAt, Instant promoExpiresAt) {}

    /** Pure timing rule above, for a checkout completed at {@code paidAt}. */
    static Schedule scheduleFor(Instant paidAt) {
        ZonedDateTime paid = paidAt.atZone(SALON_ZONE);
        LocalDate day = paid.toLocalDate();
        ZonedDateTime latest = day.atTime(LATEST_SEND).atZone(SALON_ZONE);
        ZonedDateTime due = paid.plus(SEND_DELAY);
        ZonedDateTime earliest = day.atTime(EARLIEST_SAME_DAY).atZone(SALON_ZONE);
        if (due.isBefore(earliest)) {
            due = earliest;
        }
        if (due.isAfter(latest)) {
            due = paid.plus(MIN_LEAD).isAfter(latest)
                    ? day.plusDays(1).atTime(NEXT_DAY_SEND).atZone(SALON_ZONE)
                    : latest;
        }
        LocalDate emailDay = due.toLocalTime().isBefore(EMAIL_FOLLOW_UP) ? due.toLocalDate() : due.toLocalDate().plusDays(1);
        Instant promoExpiresAt = emailDay.plusDays(1).atStartOfDay(SALON_ZONE).toInstant();
        return new Schedule(due.toInstant(), promoExpiresAt);
    }

    /** True while a rebooking text may go out: {@link #EARLIEST_SAME_DAY} to {@link #LATEST_SEND}. */
    static boolean isWithinSendWindow(Instant now) {
        LocalTime t = now.atZone(SALON_ZONE).toLocalTime();
        return !t.isBefore(EARLIEST_SAME_DAY) && !t.isAfter(LATEST_SEND);
    }

    private final SameDayRebookingSendRepository repository;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public SameDayRebookingTriggerService(SameDayRebookingSendRepository repository) {
        this(repository, Clock.system(SALON_ZONE));
    }

    /** Test-only: fixed clock. */
    SameDayRebookingTriggerService(SameDayRebookingSendRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public void enqueue(Long businessId, String paymentId, String customerId, String phoneNumber, String customerName) {
        try {
            if (repository.existsBySquarePaymentId(paymentId)) {
                return; // Square redelivered an event we already enqueued a send for
            }
            Instant now = clock.instant();
            Instant startOfToday = now.atZone(SALON_ZONE).toLocalDate().atStartOfDay(SALON_ZONE).toInstant();
            if (repository.existsByBusinessIdAndPhoneNumberAndCreatedAtAfter(businessId, phoneNumber, startOfToday)) {
                // Found live 2026-08-27 alongside CheckoutReviewTriggerService's own version of
                // this same fix: two family members checked out on separate real Square payments
                // during the same visit, and the payment-id dedup above correctly treats them as
                // two distinct events — but one same-day-rebooking nudge per visit is enough.
                log.info("Same-day-rebooking trigger skipped for {} — already sent one today (payment {} is a separate checkout on the same visit)",
                        phoneNumber, paymentId);
                return;
            }
            Schedule schedule = scheduleFor(now);
            repository.save(SameDayRebookingSend.builder()
                    .businessId(businessId)
                    .phoneNumber(phoneNumber)
                    .customerName(customerName)
                    .squareCustomerId(customerId)
                    .squarePaymentId(paymentId)
                    .sendDueAt(schedule.sendDueAt())
                    .promoExpiresAt(schedule.promoExpiresAt())
                    .state(SameDayRebookingSend.STATE_AWAITING_SEND)
                    .build());
        } catch (Exception e) {
            log.warn("Same-day-rebooking trigger failed for payment {} (event ignored): {}", paymentId, e.getMessage());
        }
    }
}
