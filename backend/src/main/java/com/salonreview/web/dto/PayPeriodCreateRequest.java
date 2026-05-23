package com.salonreview.web.dto;

import com.salonreview.domain.Half;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record PayPeriodCreateRequest(
        @Min(2000) @Max(2100) int year,
        @Min(1) @Max(12) int month,
        @NotNull Half half
) {}
