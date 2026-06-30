package com.salonreview.web;

import com.salonreview.square.RetentionAnalyticsService;
import com.salonreview.web.dto.RetentionReport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Owner-only provider retention analytics. Under {@code /api/owner/**} (OWNER-gated in SecurityConfig),
 * so managers/providers are denied. Reads the visit ledger; no Square call on request.
 */
@RestController
@RequestMapping("/api/owner")
public class RetentionController {

    private final RetentionAnalyticsService service;

    public RetentionController(RetentionAnalyticsService service) {
        this.service = service;
    }

    @GetMapping("/retention")
    public RetentionReport retention(@RequestParam(required = false) Integer year,
                                     @RequestParam(required = false) Integer month) {
        LocalDate today = LocalDate.now();
        int y = year != null ? year : today.getYear();
        int m = month != null ? month : today.getMonthValue();
        return service.report(y, m);
    }
}
