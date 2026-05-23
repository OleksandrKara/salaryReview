package com.salonreview.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record ProviderCreateRequest(
        @NotBlank String name,
        @NotBlank String displayName,
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal commissionRate,
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal cardTipFeeRate
) {}
