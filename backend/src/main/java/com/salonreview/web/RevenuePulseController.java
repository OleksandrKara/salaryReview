package com.salonreview.web;

import com.salonreview.square.RevenuePulseService;
import com.salonreview.square.RevenueSnapshotService;
import com.salonreview.web.dto.RevenueDayDetailDto;
import com.salonreview.web.dto.RevenuePulseDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/owner")
public class RevenuePulseController {

    private final RevenuePulseService service;
    private final RevenueSnapshotService snapshots;

    public RevenuePulseController(RevenuePulseService service, RevenueSnapshotService snapshots) {
        this.service = service;
        this.snapshots = snapshots;
    }

    @GetMapping("/pulse")
    public RevenuePulseDto pulse(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        LocalDate today = LocalDate.now();
        int y = year  != null ? year  : today.getYear();
        int m = month != null ? month : today.getMonthValue();
        return service.pulse(y, m);
    }

    /** What was known/projected as of a specific date — the frozen daily snapshot plus a
     * recomputed forecast, and the month's actual final total if it's since settled.
     */
    @GetMapping("/pulse/day")
    public RevenueDayDetailDto day(@RequestParam String date) {
        return snapshots.dayDetail(LocalDate.parse(date));
    }
}
