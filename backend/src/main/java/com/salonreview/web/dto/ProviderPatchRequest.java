package com.salonreview.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;

public record ProviderPatchRequest(
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal commissionRate,
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal cardTipFeeRate,
        Boolean active
) {}
