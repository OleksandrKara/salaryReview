package com.salonreview.web;

import com.salonreview.service.SettlementService;
import com.salonreview.web.dto.SettlementDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/pay-periods/{id}/settlements")
public class SettlementController {

    private final SettlementService service;

    public SettlementController(SettlementService service) {
        this.service = service;
    }

    @GetMapping
    public List<SettlementDto> settlements(@PathVariable Long id) {
        return service.settlementsFor(id);
    }
}
