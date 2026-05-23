package com.salonreview.web.dto;

import com.salonreview.domain.Provider;

import java.math.BigDecimal;

public record ProviderDto(
        Long id,
        String name,
        String displayName,
        BigDecimal commissionRate,
        BigDecimal cardTipFeeRate,
        boolean active
) {
    public static ProviderDto from(Provider p) {
        return new ProviderDto(
                p.getId(),
                p.getName(),
                p.getDisplayName(),
                p.getCommissionRate(),
                p.getCardTipFeeRate(),
                p.isActive()
        );
    }
}
