package com.salonreview.web;

import com.salonreview.marketing.AbuseBlocksService;
import com.salonreview.web.dto.AbuseBlocksDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/owner/marketing/abuse-blocks")
public class AbuseBlocksController {

    private final AbuseBlocksService service;

    public AbuseBlocksController(AbuseBlocksService service) {
        this.service = service;
    }

    @GetMapping
    public AbuseBlocksDto blocks() {
        return service.blocks();
    }
}
