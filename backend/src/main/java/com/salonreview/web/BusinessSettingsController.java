package com.salonreview.web;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.Business;
import com.salonreview.domain.SalonConfig;
import com.salonreview.service.BusinessSettingsService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/**
 * OWNER-only business name/timezone + financial config for the calling business — falls under
 * {@code /api/owner/**} in {@link com.salonreview.config.SecurityConfig}, no new security config
 * needed.
 */
@RestController
@RequestMapping("/api/owner/settings/business")
public class BusinessSettingsController {

    private final BusinessSettingsService service;
    private final CurrentBusinessContext currentBusinessContext;

    public BusinessSettingsController(BusinessSettingsService service, CurrentBusinessContext currentBusinessContext) {
        this.service = service;
        this.currentBusinessContext = currentBusinessContext;
    }

    @GetMapping
    public BusinessSettingsDto get() {
        return toDto(service.get(currentBusinessContext.id()));
    }

    @PutMapping
    public BusinessSettingsDto update(@RequestBody BusinessSettingsUpdateRequest body) {
        return toDto(service.update(currentBusinessContext.id(), body.name(), body.timezone(),
                body.ownerShortName(), body.baseCommissionRate(), body.tierEnabled(),
                body.tierServiceThreshold(), body.servicePriceCutoff(), body.cardTipFeeRate(),
                body.noShowFeeAmount()));
    }

    private static BusinessSettingsDto toDto(BusinessSettingsService.View view) {
        Business b = view.business();
        SalonConfig c = view.config();
        return new BusinessSettingsDto(b.getId(), b.getName(), b.getShortCode(), b.getTimezone(),
                view.configured(),
                c == null ? null : c.getOwnerShortName(),
                c == null ? null : c.getBaseCommissionRate(),
                c != null && c.isTierEnabled(),
                c == null ? null : c.getTierServiceThreshold(),
                c == null ? null : c.getServicePriceCutoff(),
                c == null ? null : c.getCardTipFeeRate(),
                c == null ? null : c.getNoShowFeeAmount());
    }

    public record BusinessSettingsDto(Long businessId, String name, String shortCode, String timezone,
                                       boolean configured, String ownerShortName, BigDecimal baseCommissionRate,
                                       boolean tierEnabled, Integer tierServiceThreshold,
                                       BigDecimal servicePriceCutoff, BigDecimal cardTipFeeRate,
                                       // Phase 4.4: null = no no-show fee program for this business.
                                       BigDecimal noShowFeeAmount) {
    }

    /** {@code shortCode} is deliberately absent — immutable once created (it's used as a stable
     * identifier elsewhere). Every other field is null-means-"leave unchanged" on an existing
     * config; see {@link BusinessSettingsService#update} for which are required on first setup.
     * {@code noShowFeeAmount} shares that same "null = leave unchanged" convention — there is
     * currently no way to explicitly clear it back to null once set, same limitation every other
     * optional numeric field here already has. */
    public record BusinessSettingsUpdateRequest(String name, String timezone, String ownerShortName,
                                                  BigDecimal baseCommissionRate, Boolean tierEnabled,
                                                  Integer tierServiceThreshold, BigDecimal servicePriceCutoff,
                                                  BigDecimal cardTipFeeRate, BigDecimal noShowFeeAmount) {
    }
}
