package com.salonreview.service;

import com.salonreview.domain.PayPeriod;
import com.salonreview.domain.PeriodEntry;
import com.salonreview.domain.Provider;
import com.salonreview.repo.PayPeriodRepository;
import com.salonreview.repo.PeriodEntryRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.SalonConfigRepository;
import com.salonreview.web.dto.SettlementDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;

@Service
public class SettlementService {

    private final PayPeriodRepository periods;
    private final PeriodEntryRepository entries;
    private final ProviderRepository providers;
    private final SalonConfigRepository salonConfig;
    private final CommissionCalculator calculator;
    private final MessageFormatter formatter;

    public SettlementService(PayPeriodRepository periods,
                             PeriodEntryRepository entries,
                             ProviderRepository providers,
                             SalonConfigRepository salonConfig,
                             CommissionCalculator calculator,
                             MessageFormatter formatter) {
        this.periods = periods;
        this.entries = entries;
        this.providers = providers;
        this.salonConfig = salonConfig;
        this.calculator = calculator;
        this.formatter = formatter;
    }

    @Transactional(readOnly = true)
    public List<SettlementDto> settlementsFor(Long payPeriodId) {
        PayPeriod period = periods.findById(payPeriodId)
                .orElseThrow(() -> new NoSuchElementException("Pay period " + payPeriodId + " not found"));

        String owner = salonConfig.findById(1)
        .orElseThrow(() -> new IllegalStateException("Salon config with id=1 is missing"))
        .getOwnerShortName();

        Map<Long, PeriodEntry> entryByProvider = entries.findAllByPayPeriodId(payPeriodId).stream()
                .collect(java.util.stream.Collectors.toMap(e -> e.getProvider().getId(), Function.identity()));

        return providers.findAllByActiveTrue().stream()
                .sorted(Comparator.comparing(p -> p.getDisplayName().toLowerCase()))
                .map(provider -> {
                    PeriodEntry entry = entryByProvider.getOrDefault(provider.getId(), zeroEntry(provider, period));
                    SettlementLine line = calculator.calculate(provider, entry);
                    String message = formatter.format(period, provider, owner, line);
                    return new SettlementDto(
                            line.providerId(),
                            line.providerName(),
                            line.procedures(),
                            line.cardTotal(),
                            line.cashTotal(),
                            line.cardTips(),
                            line.tipsAfterFee(),
                            line.adjustments(),
                            line.zelleToProvider(),
                            line.cashToSalon(),
                            message
                    );
                })
                .toList();
    }

    private static PeriodEntry zeroEntry(Provider provider, PayPeriod period) {
        return PeriodEntry.builder()
                .provider(provider)
                .payPeriod(period)
                .procedures(0)
                .cardTotal(BigDecimal.ZERO)
                .cashTotal(BigDecimal.ZERO)
                .cardTips(BigDecimal.ZERO)
                .adjustmentsAmount(BigDecimal.ZERO)
                .build();
    }
}
