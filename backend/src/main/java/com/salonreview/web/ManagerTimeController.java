package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.square.ManagerTimeService;
import com.salonreview.square.ManagerTimeService.ManualEntry;
import com.salonreview.web.dto.AdminTimesheetDto;
import com.salonreview.web.dto.ManagerTimesheetDto;
import com.salonreview.web.dto.TimeEntryDto;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Manager time tracking. The self endpoints ({@code /me}, clock in/out, entry CRUD) always act on the
 * authenticated caller — never a request parameter — so a manager can only ever touch their own time.
 * The {@code /admin} endpoints are owner-only (gated in SecurityConfig) for the payroll view and for
 * setting a manager's hourly rate.
 */
@RestController
@RequestMapping("/api/time")
public class ManagerTimeController {

    private final ManagerTimeService service;

    public ManagerTimeController(ManagerTimeService service) {
        this.service = service;
    }

    // --- manager self ---

    @GetMapping("/me")
    public ManagerTimesheetDto myTimesheet(@AuthenticationPrincipal AppUserPrincipal me,
                                           @RequestParam(required = false) Integer year,
                                           @RequestParam(required = false) Integer month) {
        LocalDate today = LocalDate.now();
        int y = year != null ? year : today.getYear();
        int m = month != null ? month : today.getMonthValue();
        return service.myTimesheet(me.getUserId(), y, m);
    }

    @PostMapping("/clock-in")
    public TimeEntryDto clockIn(@AuthenticationPrincipal AppUserPrincipal me) {
        return service.clockIn(me.getUserId());
    }

    @PostMapping("/clock-out")
    public TimeEntryDto clockOut(@AuthenticationPrincipal AppUserPrincipal me) {
        return service.clockOut(me.getUserId());
    }

    @PostMapping("/entries")
    public TimeEntryDto add(@AuthenticationPrincipal AppUserPrincipal me, @RequestBody ManualEntry req) {
        return service.addEntry(me.getUserId(), req);
    }

    @PatchMapping("/entries/{id}")
    public TimeEntryDto update(@AuthenticationPrincipal AppUserPrincipal me,
                               @PathVariable Long id, @RequestBody ManualEntry req) {
        return service.updateEntry(me.getUserId(), id, req);
    }

    @DeleteMapping("/entries/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long id) {
        service.deleteEntry(me.getUserId(), id);
        return ResponseEntity.noContent().build();
    }

    // --- owner ---

    @GetMapping("/admin")
    public AdminTimesheetDto admin(@RequestParam(required = false) Integer year,
                                   @RequestParam(required = false) Integer month) {
        LocalDate today = LocalDate.now();
        int y = year != null ? year : today.getYear();
        int m = month != null ? month : today.getMonthValue();
        return service.adminTimesheets(y, m);
    }

    @PutMapping("/admin/rate/{userId}")
    public ResponseEntity<Void> setRate(@PathVariable Long userId, @RequestBody RateRequest req,
                                        @AuthenticationPrincipal AppUserPrincipal me) {
        service.setRate(userId, req.usdPerHour(), me == null ? null : me.getUsername());
        return ResponseEntity.ok().build();
    }

    public record RateRequest(BigDecimal usdPerHour) {}
}
