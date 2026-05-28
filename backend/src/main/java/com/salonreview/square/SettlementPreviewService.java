package com.salonreview.square;

import com.salonreview.commission.CommissionConfig;
import com.salonreview.commission.HalfInput;
import com.salonreview.commission.HalfSettlement;
import com.salonreview.commission.TierCommissionEngine;
import com.salonreview.domain.Provider;
import com.salonreview.domain.SalonConfig;
import com.salonreview.domain.SettlementFeedback;
import com.salonreview.domain.TierGrant;
import com.salonreview.repo.SalonConfigRepository;
import com.salonreview.repo.SettlementFeedbackRepository;
import com.salonreview.repo.TierGrantRepository;
import com.salonreview.service.ProviderDirectory;
import com.salonreview.square.SquareMonthAggregator.MonthAggregation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Month settlement: aggregates a month of Square data, maps each Square team member to a provider
 * (person), merges their halves, and runs the {@link TierCommissionEngine} using the salon's stored
 * config. Read-only with respect to settlements (nothing is persisted yet) but it does auto-provision
 * provider records via {@link ProviderDirectory}.
 */
@Service
public class SettlementPreviewService {

    private final SquareMonthAggregator aggregator;
    private final TierCommissionEngine engine;
    private final SalonConfigRepository salonConfig;
    private final ProviderDirectory directory;
    private final TierGrantRepository tierGrants;
    private final SettlementFeedbackRepository feedback;

    public SettlementPreviewService(SquareMonthAggregator aggregator, TierCommissionEngine engine,
                                    SalonConfigRepository salonConfig, ProviderDirectory directory,
                                    TierGrantRepository tierGrants, SettlementFeedbackRepository feedback) {
        this.aggregator = aggregator;
        this.engine = engine;
        this.salonConfig = salonConfig;
        this.directory = directory;
        this.tierGrants = tierGrants;
        this.feedback = feedback;
    }

    @Transactional
    public SettlementPreview preview(int year, int month) {
        SalonConfig sc = salonConfig.findById(1)
                .orElseThrow(() -> new IllegalStateException("Salon config with id=1 is missing"));
        CommissionConfig config = sc.toCommissionConfig();
        BigDecimal cutoff = sc.getServicePriceCutoff();

        Set<Long> tierGrantedProviderIds = tierGrants.findByYearAndMonth(year, month).stream()
                .map(TierGrant::getProviderId).collect(Collectors.toSet());

        Map<Long, SettlementFeedback> feedbackByProvider = feedback.findByYearAndMonth(year, month).stream()
                .collect(Collectors.toMap(SettlementFeedback::getProviderId, f -> f, (a, b) -> a));

        MonthAggregation agg = aggregator.aggregate(year, month, cutoff);

        // Collapse Square team members into provider persons (merging any duplicate accounts).
        Map<Long, Merged> byPerson = new LinkedHashMap<>();
        for (var pm : agg.providers()) {
            Provider person = directory.resolveOrCreate(pm.providerId(), pm.name());
            Merged m = byPerson.computeIfAbsent(person.getId(),
                    k -> new Merged(person.getId(), person.getDisplayName()));
            m.first = sum(m.first, pm.firstHalf());
            m.second = sum(m.second, pm.secondHalf());
        }

        List<ProviderPayout> payouts = byPerson.values().stream().map(m -> {
            int monthCounted = m.first.countedServices() + m.second.countedServices();
            boolean autoQualified = monthCounted >= config.tierServiceThreshold();
            boolean granted = tierGrantedProviderIds.contains(m.providerId);
            Boolean tierGrant = granted ? Boolean.TRUE : null;

            HalfSettlement first = engine.firstHalf(m.first, config);
            HalfSettlement second = engine.secondHalfFinal(m.first, m.second, config, tierGrant);
            BigDecimal monthZelle = first.zelleToProvider().add(second.zelleToProvider());
            BigDecimal monthCashToSalon = first.cashToSalon().add(second.cashToSalon());
            SettlementFeedback fb = feedbackByProvider.get(m.providerId);
            return new ProviderPayout(m.providerId, m.name, monthCounted, autoQualified,
                    granted && !autoQualified, autoQualified || granted,
                    first, second, monthZelle, monthCashToSalon,
                    fb != null ? fb.getStatus().name() : null,
                    fb != null ? fb.getComment() : null);
        }).sorted(Comparator.comparing(p -> p.name().toLowerCase())).toList();

        return new SettlementPreview(year, month, agg.timezone(), config, cutoff, payouts, agg.diagnostics());
    }

    /**
     * The settlement for a single provider — used by the provider self-view. Returns their payout
     * (with any feedback) or {@code null} if they had no activity that month.
     */
    @Transactional
    public ProviderPayout previewForProvider(int year, int month, Long providerId) {
        return preview(year, month).providers().stream()
                .filter(p -> p.providerId().equals(providerId))
                .findFirst()
                .orElse(null);
    }

    private static HalfInput sum(HalfInput a, HalfInput b) {
        return new HalfInput(
                a.countedServices() + b.countedServices(),
                a.cardRevenue().add(b.cardRevenue()),
                a.cardTips().add(b.cardTips()),
                a.cashTotal().add(b.cashTotal()),
                a.adjustments().add(b.adjustments()));
    }

    private static final class Merged {
        final Long providerId;
        final String name;
        HalfInput first = HalfInput.empty();
        HalfInput second = HalfInput.empty();

        Merged(Long providerId, String name) {
            this.providerId = providerId;
            this.name = name;
        }
    }

    public record SettlementPreview(int year, int month, String timezone, CommissionConfig config,
                                    BigDecimal priceCutoff, List<ProviderPayout> providers,
                                    SquareMonthAggregator.Diag diagnostics) {}

    public record ProviderPayout(Long providerId, String name, int monthCountedServices,
                                 boolean autoQualified, boolean tierManuallyGranted, boolean tierApplied,
                                 HalfSettlement firstHalf, HalfSettlement secondHalf,
                                 BigDecimal monthZelleToProvider, BigDecimal monthCashToSalon,
                                 String feedbackStatus, String feedbackComment) {}
}
