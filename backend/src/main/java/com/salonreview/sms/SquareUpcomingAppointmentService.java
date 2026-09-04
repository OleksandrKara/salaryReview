package com.salonreview.sms;

import com.salonreview.square.SquareBookingFilters;
import com.salonreview.square.SquareClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Shared "does this person already have an upcoming appointment" check for
 * {@code LapsedCustomerWinbackScheduler}/{@code RepeatCustomerWinbackScheduler}/
 * {@code SameDayRebookingScheduler}/{@code LeadFollowUpScheduler} — each used to duplicate this
 * exact same {@code bookingsForCustomer} + {@link SquareBookingFilters} logic independently,
 * scoped to one specific {@code square_customer_id}.
 *
 * <p>Extracted here after a real production bug (found 2026-09-04): Square lets the same phone
 * number end up with more than one customer profile (confirmed live — 33 phone numbers on
 * business 1 and 109 on business 2 each have 2+ distinct {@code square_customer_id}s in our own
 * {@code square_customer} mirror), so a check scoped to a single customer_id can genuinely see "no
 * upcoming bookings" while a sibling profile under the exact same phone number has one. This is
 * exactly what happened to a real lapsed-customer-winback recipient: her one counted visit was
 * attributed to one Square customer profile, her upcoming appointment was booked under a second,
 * separate profile Square created for the same phone number — the old per-customer-id check
 * correctly saw no upcoming booking for the profile it was given, it just wasn't the whole
 * picture. Checking every profile {@link SquareClient#customerIdsForPhone} returns for that phone
 * number (a live Square search, not our possibly-stale mirror) closes that gap regardless of which
 * of a person's duplicate profiles ends up with the booking.
 */
@Component
public class SquareUpcomingAppointmentService {

    /** True if any Square customer profile sharing this phone number has an appointment that
     * hasn't happened yet (not cancelled/declined/no-show, and today or later — see {@link
     * SquareBookingFilters}). Empty/unresolvable phone number or no matching Square profiles at
     * all means false, same "can't confirm, so don't block" semantics each caller already had. */
    public boolean hasUpcomingAppointment(String phoneNumber, SquareClient square) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            return false;
        }
        List<String> customerIds = square.customerIdsForPhone(phoneNumber);
        if (customerIds.isEmpty()) {
            return false;
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return customerIds.stream().anyMatch(customerId ->
                square.bookingsForCustomer(customerId, Instant.now()).stream()
                        .filter(SquareBookingFilters::didHappen)
                        .anyMatch(b -> SquareBookingFilters.isTodayOrLater(b.startAt(), today)));
    }
}
