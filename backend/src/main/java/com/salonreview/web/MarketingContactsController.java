package com.salonreview.web;

import com.salonreview.marketing.MarketingContactsService;
import com.salonreview.web.dto.MarketingContactDto;
import com.salonreview.web.dto.MarketingSyncStatusDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/owner/marketing/contacts")
public class MarketingContactsController {

    /** Matches the frontend's scroll-batch size (see ContactsTable) — a generous ceiling against a
     * malformed/adversarial request, not a normal-path limit. */
    private static final int MAX_ENRICH_BATCH = 100;

    private final MarketingContactsService service;

    public MarketingContactsController(MarketingContactsService service) {
        this.service = service;
    }

    @GetMapping
    public MarketingContactDto contacts() {
        return service.contacts();
    }

    public record EnrichRequest(List<String> contactIds) {}

    /** Lazy follow-up for the rows actually scrolled into view — see
     * MarketingContactsService#enrichContacts for why {@link #contacts} no longer includes this
     * itself. */
    @PostMapping("/enrich")
    public Map<String, MarketingContactsService.ContactEnrichment> enrich(@RequestBody EnrichRequest request) {
        List<String> ids = request.contactIds() == null ? List.of() : request.contactIds();
        return service.enrichContacts(ids.subList(0, Math.min(ids.size(), MAX_ENRICH_BATCH)));
    }

    /** When "Sync appointments" was last run — read separately from the POST below so
     * MarketingTabs (rendered on every marketing tab) can show it on page load without paying for
     * a full contacts fetch just to get one timestamp. */
    @GetMapping("/sync")
    public MarketingSyncStatusDto syncStatus() {
        return new MarketingSyncStatusDto(service.lastSyncedAt());
    }

    /** "Sync appointments" — owner-only (falls through to the general /api/owner/** catch-all in
     * SecurityConfig; the GET mappings above are the exceptions, opened to ADS_MANAGER too).
     * Resolves any lead that never linked to a Square customer through the tracked booking flow,
     * then returns the refreshed contact list. */
    @PostMapping("/sync")
    public MarketingContactDto sync() {
        return service.syncSquareLinks();
    }
}
