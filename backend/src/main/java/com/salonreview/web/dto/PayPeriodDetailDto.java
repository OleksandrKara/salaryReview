package com.salonreview.web.dto;

import java.util.List;

public record PayPeriodDetailDto(
        PayPeriodDto period,
        List<PeriodEntryDto> entries
) {}
