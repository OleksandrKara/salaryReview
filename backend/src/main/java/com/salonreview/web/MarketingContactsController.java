package com.salonreview.web;

import com.salonreview.marketing.MarketingContactsService;
import com.salonreview.web.dto.MarketingContactDto;
import com.salonreview.web.dto.MarketingContactHistoryDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

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

    /** Lazy/on-demand: Square appointment history + submission history for one contact, fetched
     * only when the owner expands it, so the main list never pays for N Square API calls.
     */
    @GetMapping("/{contactId}/history")
    public MarketingContactHistoryDto history(@PathVariable UUID contactId) {
        return service.history(contactId);
    }
}
