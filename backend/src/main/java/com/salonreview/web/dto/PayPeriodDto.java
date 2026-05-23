package com.salonreview.web.dto;

import com.salonreview.domain.Half;
import com.salonreview.domain.PayPeriod;

public record PayPeriodDto(
        Long id,
        int year,
        int month,
        Half half,
        String label
) {
    public static PayPeriodDto from(PayPeriod p) {
        return new PayPeriodDto(p.getId(), p.getYear(), p.getMonth(), p.getHalf(), p.getLabel());
    }
}
