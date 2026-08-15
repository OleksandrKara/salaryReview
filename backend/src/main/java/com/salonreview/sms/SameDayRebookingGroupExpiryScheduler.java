package com.salonreview.sms;

import com.salonreview.config.RebookingProperties;
import com.salonreview.domain.SameDayRebookingGroupMembership;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.repo.SameDayRebookingGroupMembershipRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Removes a customer from whichever Square auto-discount customer group they were enrolled into
 * once their personal offer window expires — originally just the $10 same_day_rebooking_discount
 * group (design.md D7), now shared with the $5 lapsed_customer_winback group too (see V71 and
 * {@link SameDayRebookingGroupMembership#getGroupId()}). A 60s poll is plenty precise here (unlike
 * the send schedulers' 15s — this isn't customer-facing, it just needs to happen sometime after
 * expiry). Fails closed on a Square error: the row is left unremoved and retried next tick rather
 * than marking it done and silently leaving the customer enrolled indefinitely.
 */
@Component
public class SameDayRebookingGroupExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(SameDayRebookingGroupExpiryScheduler.class);

    private final SameDayRebookingGroupMembershipRepository repository;
    private final SquareClientProvider squareClientProvider;
    private final BusinessRepository businesses;
    private final RebookingProperties rebookingProperties;

    public SameDayRebookingGroupExpiryScheduler(SameDayRebookingGroupMembershipRepository repository,
                                                 SquareClientProvider squareClientProvider, BusinessRepository businesses,
                                                 RebookingProperties rebookingProperties) {
        this.repository = repository;
        this.squareClientProvider = squareClientProvider;
        this.businesses = businesses;
        this.rebookingProperties = rebookingProperties;
    }

    // initialDelay: see SameDayRebookingScheduler's identical comment — gives
    // SquareConnectionBootstrap's ApplicationRunner time to finish before the first tick.
    @Scheduled(fixedDelay = 60_000, initialDelay = 15_000)
    @SchedulerLock(name = "SameDayRebookingGroupExpiryScheduler_removeExpiredMemberships", lockAtLeastFor = "PT10S", lockAtMostFor = "PT3M")
    public void removeExpiredMemberships() {
        // Same single-business guard as BusinessRepository#sole's own doc comment: the underlying
        // same_day_rebooking_group_membership row (and every other SMS-scheduler table read in this
        // package) has no business_id of its own yet, so there is no correct way to route this
        // customer to the right Square account once a second business exists — failing loudly here
        // is safer than silently processing every business's rows against business A's Square
        // account. Scoping the sms/marketing schedulers is tracked separately from the
        // SquareClientProvider migration itself.
        SquareClient square = squareClientProvider.forBusiness(businesses.sole().getId());
        Instant now = Instant.now();
        List<SameDayRebookingGroupMembership> due = repository.findByRemovedAtIsNullAndExpiresAtBefore(now);
        for (SameDayRebookingGroupMembership membership : due) {
            // Rows written before V71 have no groupId of their own — they were all $10
            // same-day-rebooking enrollments (the $5 winback group didn't exist yet), so falling
            // back to that group id here reproduces exactly what this scheduler always did.
            String groupId = membership.getGroupId() != null
                    ? membership.getGroupId()
                    : rebookingProperties.getAutoDiscountGroupId();
            if (groupId == null || groupId.isBlank()) {
                // Nothing to remove from — same "don't guess" convention as everywhere else this
                // config is checked. Leaves the row unremoved for a future run once configured,
                // rather than marking it done against a group that was never real.
                continue;
            }
            try {
                square.removeCustomerFromGroup(membership.getSquareCustomerId(), groupId);
                membership.setRemovedAt(Instant.now());
                repository.save(membership);
            } catch (RuntimeException e) {
                log.warn("Failed to remove customer {} from group {} (retrying next tick): {}",
                        membership.getSquareCustomerId(), groupId, e.getMessage());
            }
        }
    }
}
