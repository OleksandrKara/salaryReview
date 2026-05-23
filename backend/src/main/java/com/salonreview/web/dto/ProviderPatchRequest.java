package com.salonreview.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record ProviderPatchRequest(
        @Size(min = 1, max = 200) String name,
        @Size(min = 1, max = 100) String displayName,
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal commissionRate,
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal cardTipFeeRate,
        Boolean active
) {}
