package com.salonreview.web;

import com.salonreview.marketing.MarketingContactsService;
import com.salonreview.web.dto.MarketingContactDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/owner/marketing/contacts")
public class MarketingContactsController {

    private final MarketingContactsService service;

    public MarketingContactsController(MarketingContactsService service) {
        this.service = service;
    }

    @GetMapping
    public MarketingContactDto contacts() {
        return service.contacts();
    }

    /** "Sync appointments" — owner-only (falls through to the general /api/owner/** catch-all in
     * SecurityConfig; the GET above is the one exception, opened to ADS_MANAGER too). Resolves any
     * lead that never linked to a Square customer through the tracked booking flow, then returns
     * the refreshed contact list. */
    @PostMapping("/sync")
    public MarketingContactDto sync() {
        return service.syncSquareLinks();
    }
}
