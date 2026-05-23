package com.salonreview.web.dto;

import java.math.BigDecimal;

public record SettlementDto(
        Long providerId,
        String providerName,
        int procedures,
        BigDecimal cardTotal,
        BigDecimal cashTotal,
        BigDecimal cardTips,
        BigDecimal tipsAfterFee,
        BigDecimal adjustments,
        BigDecimal zelleToProvider,
        BigDecimal cashToSalon,
        String messageText
) {}
