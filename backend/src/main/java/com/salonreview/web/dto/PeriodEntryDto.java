package com.salonreview.web.dto;

import com.salonreview.domain.PeriodEntry;

import java.math.BigDecimal;

public record PeriodEntryDto(
        Long id,
        Long providerId,
        String providerDisplayName,
        Long payPeriodId,
        int procedures,
        BigDecimal cardTotal,
        BigDecimal cashTotal,
        BigDecimal cardTips,
        BigDecimal adjustmentsAmount,
        String adjustmentsNote
) {
    public static PeriodEntryDto from(PeriodEntry e) {
        return new PeriodEntryDto(
                e.getId(),
                e.getProvider().getId(),
                e.getProvider().getDisplayName(),
                e.getPayPeriod().getId(),
                e.getProcedures(),
                e.getCardTotal(),
                e.getCashTotal(),
                e.getCardTips(),
                e.getAdjustmentsAmount(),
                e.getAdjustmentsNote()
        );
    }
}
