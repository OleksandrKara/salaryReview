package com.salonreview.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;

public record PeriodEntryUpsertRequest(
        @PositiveOrZero int procedures,
        @NotNull @DecimalMin("0.0") BigDecimal cardTotal,
        @NotNull @DecimalMin("0.0") BigDecimal cashTotal,
        @NotNull @DecimalMin("0.0") BigDecimal cardTips,
        @NotNull BigDecimal adjustmentsAmount,
        String adjustmentsNote,
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal commissionRate
) {}
