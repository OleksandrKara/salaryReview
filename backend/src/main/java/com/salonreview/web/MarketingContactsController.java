package com.salonreview.web;

import com.salonreview.marketing.MarketingContactsService;
import com.salonreview.web.dto.MarketingContactDto;
import org.springframework.web.bind.annotation.GetMapping;
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
}
