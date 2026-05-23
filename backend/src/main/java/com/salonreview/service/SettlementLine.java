package com.salonreview.service;

import java.math.BigDecimal;

public record SettlementLine(
        Long providerId,
        String providerName,
        int procedures,
        BigDecimal cardTotal,
        BigDecimal cashTotal,
        BigDecimal cardTips,
        BigDecimal tipsAfterFee,
        BigDecimal adjustments,
        BigDecimal zelleToProvider,
        BigDecimal cashToSalon
) {}
