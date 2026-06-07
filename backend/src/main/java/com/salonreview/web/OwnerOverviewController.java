package com.salonreview.web;

import com.salonreview.square.OwnerOverviewService;
import com.salonreview.web.dto.OwnerOverviewDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/owner")
public class OwnerOverviewController {

    private final OwnerOverviewService service;

    public OwnerOverviewController(OwnerOverviewService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public OwnerOverviewDto overview(
            @RequestParam(required = false) Integer fromYear,
            @RequestParam(required = false) Integer fromMonth,
            @RequestParam(required = false) Integer toYear,
            @RequestParam(required = false) Integer toMonth) {
        LocalDate today = LocalDate.now();
        int fy = fromYear  != null ? fromYear  : today.getYear();
        int fm = fromMonth != null ? fromMonth : 1;
        int ty = toYear    != null ? toYear    : today.getYear();
        int tm = toMonth   != null ? toMonth   : today.getMonthValue();
        return service.overview(fy, fm, ty, tm);
    }
}
