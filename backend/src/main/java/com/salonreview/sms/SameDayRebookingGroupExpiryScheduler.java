package com.salonreview.sms;

import com.salonreview.config.RebookingProperties;
import com.salonreview.domain.SameDayRebookingGroupMembership;
import com.salonreview.repo.SameDayRebookingGroupMembershipRepository;
import com.salonreview.square.SquareClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Removes a customer from the Square "same-day rebooking" auto-discount customer group once their
 * personal offer window expires — see openspec/changes/same-day-rebooking-discount design.md D7.
 * A 60s poll is plenty precise here (unlike the send schedulers' 15s — this isn't customer-facing,
 * it just needs to happen sometime after expiry). Fails closed on a Square error: the row is left
 * unremoved and retried next tick rather than marking it done and silently leaving the customer
 * enrolled indefinitely.
 */
@Component
public class SameDayRebookingGroupExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(SameDayRebookingGroupExpiryScheduler.class);

    private final SameDayRebookingGroupMembershipRepository repository;
    private final SquareClient square;
    private final RebookingProperties rebookingProperties;

    public SameDayRebookingGroupExpiryScheduler(SameDayRebookingGroupMembershipRepository repository,
                                                 SquareClient square, RebookingProperties rebookingProperties) {
        this.repository = repository;
        this.square = square;
        this.rebookingProperties = rebookingProperties;
    }

    @Scheduled(fixedDelay = 60_000)
    public void removeExpiredMemberships() {
        if (!rebookingProperties.isAutoDiscountConfigured()) {
            return; // one-time Square Catalog/CustomerGroup setup hasn't happened yet
        }
        Instant now = Instant.now();
        List<SameDayRebookingGroupMembership> due = repository.findByRemovedAtIsNullAndExpiresAtBefore(now);
        for (SameDayRebookingGroupMembership membership : due) {
            try {
                square.removeCustomerFromGroup(membership.getSquareCustomerId(), rebookingProperties.getAutoDiscountGroupId());
                membership.setRemovedAt(Instant.now());
                repository.save(membership);
            } catch (RuntimeException e) {
                log.warn("Failed to remove customer {} from same-day-rebooking group (retrying next tick): {}",
                        membership.getSquareCustomerId(), e.getMessage());
            }
        }
    }
}
