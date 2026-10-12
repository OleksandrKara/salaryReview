package com.salonreview.marketing;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.SquareConnection;
import com.salonreview.repo.SquareConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the Marketing Contacts list warm (owner report 2026-10-12: the tab took long to open).
 * The list is cached for 10 minutes ({@link MarketingContactsService}); rebuilding it costs a few
 * seconds for a business with hundreds of contacts, and that cost landed on whoever opened the tab
 * after the cache expired. This refreshes every business's entry every 8 minutes, so the cache
 * never expires and the tab opens from memory.
 *
 * <p>No ShedLock on purpose: the cache lives in each backend replica's own memory, so each replica
 * (blue/green) warms its own. Square cost per run is small: catalog lookups are cached per id and
 * consent segments come from one bulk call per 100 contacts.
 */
@Component
public class ContactsCacheWarmer {

    private static final Logger log = LoggerFactory.getLogger(ContactsCacheWarmer.class);

    private final SquareConnectionRepository connections;
    private final MarketingContactsService contactsService;
    private final CurrentBusinessContext currentBusinessContext;

    public ContactsCacheWarmer(SquareConnectionRepository connections, MarketingContactsService contactsService,
                               CurrentBusinessContext currentBusinessContext) {
        this.connections = connections;
        this.contactsService = contactsService;
        this.currentBusinessContext = currentBusinessContext;
    }

    @Scheduled(fixedDelay = 480_000, initialDelay = 90_000)
    public void warm() {
        for (SquareConnection connection : connections.findAll()) {
            Long businessId = connection.getBusinessId();
            try {
                currentBusinessContext.runAs(businessId, contactsService::warmContactsCache);
            } catch (RuntimeException e) {
                log.warn("Contacts cache warm-up failed for business {} (will retry next run): {}", businessId, e.getMessage());
            }
        }
    }
}
