package com.salonreview.web;

import com.salonreview.square.RevenuePulseService;
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

    public RevenuePulseController(RevenuePulseService service) {
        this.service = service;
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
}
