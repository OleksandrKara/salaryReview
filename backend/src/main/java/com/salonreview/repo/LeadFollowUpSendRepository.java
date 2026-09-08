package com.salonreview.repo;

import com.salonreview.domain.LeadFollowUpSend;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface LeadFollowUpSendRepository extends JpaRepository<LeadFollowUpSend, Long> {

    /** Belt-and-suspenders alongside the poll query's own {@code NOT EXISTS} — see
     * LeadFollowUpScheduler. True only if this exact touch (or a later one) has already been
     * processed — a contact whose updated_at has since moved past what's on file here is a new,
     * still-eligible touch, not a duplicate. */
    boolean existsByContactIdAndContactUpdatedAtGreaterThanEqual(UUID contactId, Instant contactUpdatedAt);

    /** A real production duplicate (2026-09-05): a lead who submits contact info twice within
     * minutes (a double form submit, a retried submission that looked like it failed) bumps
     * {@code marketing.contacts.updated_at} twice, and each bump is a genuinely distinct {@code
     * contactUpdatedAt} to {@link #existsByContactIdAndContactUpdatedAtGreaterThanEqual} — by
     * design (see {@link LeadFollowUpSend}'s own doc: a lead resubmitting *is* meant to count as a
     * new, nudge-worthy touch), so that check alone let the identical text go out twice, four
     * minutes apart. This is the second, phone-number-scoped guard {@link
     * com.salonreview.sms.LeadFollowUpScheduler} checks first — a resubmission within the cooldown
     * reads as the same confused moment, not a new inquiry worth a second nudge; one genuinely
     * later (days, not minutes) still gets one. */
    boolean existsByPhoneNumberAndStateAndCreatedAtAfter(String phoneNumber, String state, Instant after);

    /** Step 2 candidates: a touch whose own SMS actually sent, not yet considered for the email
     * follow-up, old enough (~24h) but not so old the poll's own window has long since passed —
     * see {@code LeadFollowUpScheduler}. */
    List<LeadFollowUpSend> findByStateAndEmailFollowupStateIsNullAndCreatedAtBetween(
            String state, Instant createdAfter, Instant createdBefore);

    /** Step 3 candidates: same shape, ~72h, independent of whether step 2 ever completed (a
     * skipped/failed email doesn't block the final SMS). */
    List<LeadFollowUpSend> findByStateAndSmsFollowupStateIsNullAndCreatedAtBetween(
            String state, Instant createdAfter, Instant createdBefore);

    /** Steps 2/3's own sibling of {@link #existsByPhoneNumberAndStateAndCreatedAtAfter} — found
     * live 2026-09-08 (owner report, customer "Stacy"): the 2026-09-05 fix stops step 1 from
     * double-touching a phone number close together, but two touches created *before* that fix
     * shipped (a real pre-fix double form submit) each independently ran their own step 2/3
     * timers with no equivalent guard at those steps, so the identical final SMS went out twice,
     * 72h later. Bounded by the *candidate touch's own* {@code createdAt} (not {@code now}) — step
     * 2/3 fire days after creation, so "now" would never overlap another close-together touch's
     * own creation window the way it correctly does for step 1's ~2-minute-later check. A touch
     * created weeks after an earlier one is a genuinely separate engagement and still gets its own
     * full sequence, since it falls outside this window. */
    boolean existsByPhoneNumberAndEmailFollowupStateAndCreatedAtBetween(
            String phoneNumber, String emailFollowupState, Instant createdAfter, Instant createdBefore);

    /** Same guard, for step 3 (the final SMS) — the specific check that would have prevented the
     * real duplicate final SMS described above. */
    boolean existsByPhoneNumberAndSmsFollowupStateAndCreatedAtBetween(
            String phoneNumber, String smsFollowupState, Instant createdAfter, Instant createdBefore);
}
