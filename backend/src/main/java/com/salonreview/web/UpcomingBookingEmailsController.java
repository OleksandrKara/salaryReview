package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.domain.SquareBookingMirror;
import com.salonreview.repo.PlatformAdminRepository;
import com.salonreview.repo.SquareBookingMirrorRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One-off, read-only report (owner request 2026-09-06, Labor Day promo): every real customer email
 * for a business who currently has an upcoming (not-yet-happened) accepted booking — used to build
 * a Mailchimp exclusion segment for a campaign the owner sends manually, so someone who already has
 * a future visit scheduled doesn't get a "book by X" offer that doesn't apply to them. Same access
 * shape as {@link ColorBoosterWinbackOneOffController}: {@code /api/platform/**} already requires
 * {@code hasRole("OWNER")}, this additionally requires a {@code platform_admin} row.
 *
 * <p>Bounded to a year out (same "a booking further out than this is vanishingly rare and not
 * worth an unbounded scan" reasoning as every other bounded-window query in this codebase) — a
 * booking scheduled further ahead than that would still show up next time this report is run.
 */
@RestController
@RequestMapping("/api/platform/one-off/upcoming-booking-emails")
public class UpcomingBookingEmailsController {

    private static final Duration LOOKAHEAD = Duration.ofDays(365);

    private final SquareBookingMirrorRepository bookingMirrorRepository;
    private final SquareClientProvider squareClientProvider;
    private final PlatformAdminRepository platformAdmins;

    public UpcomingBookingEmailsController(SquareBookingMirrorRepository bookingMirrorRepository,
                                            SquareClientProvider squareClientProvider,
                                            PlatformAdminRepository platformAdmins) {
        this.bookingMirrorRepository = bookingMirrorRepository;
        this.squareClientProvider = squareClientProvider;
        this.platformAdmins = platformAdmins;
    }

    public record ResultDto(int upcomingCustomerCount, int resolvedEmailCount, List<String> emails) {}

    @GetMapping
    public ResultDto get(@RequestParam Long businessId, @AuthenticationPrincipal AppUserPrincipal principal) {
        if (!platformAdmins.existsById(principal.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Platform admin access required");
        }

        Instant now = Instant.now();
        Set<String> customerIds = new HashSet<>();
        for (SquareBookingMirror booking : bookingMirrorRepository.findByBusinessIdAndStartAtBetween(
                businessId, now, now.plus(LOOKAHEAD))) {
            if ("ACCEPTED".equals(booking.getStatus()) && booking.getSquareCustomerId() != null) {
                customerIds.add(booking.getSquareCustomerId());
            }
        }

        SquareClient square = squareClientProvider.forBusiness(businessId);
        List<String> emails = new ArrayList<>();
        for (String customerId : customerIds) {
            String email = square.customerEmail(customerId);
            if (email != null && !email.isBlank()) {
                emails.add(email);
            }
        }
        return new ResultDto(customerIds.size(), emails.size(), emails);
    }
}
