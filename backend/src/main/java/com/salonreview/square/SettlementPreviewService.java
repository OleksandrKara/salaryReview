package com.salonreview.square;

import com.salonreview.commission.CommissionConfig;
import com.salonreview.commission.HalfInput;
import com.salonreview.commission.HalfSettlement;
import com.salonreview.commission.TierCommissionEngine;
import com.salonreview.domain.Half;
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
    private final SquareClient square;

    public SettlementPreviewService(SquareMonthAggregator aggregator, TierCommissionEngine engine,
                                    SalonConfigRepository salonConfig, ProviderDirectory directory,
                                    TierGrantRepository tierGrants, SettlementFeedbackRepository feedback,
                                    SquareClient square) {
        this.aggregator = aggregator;
        this.engine = engine;
        this.salonConfig = salonConfig;
        this.directory = directory;
        this.tierGrants = tierGrants;
        this.feedback = feedback;
        this.square = square;
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
        Map<Long, Merged> byPerson = collapseToPersons(agg);
        Map<Long, int[]> procedures = procedureCounts(agg, byPerson);
        Map<Long, BigDecimal[]> discounts = discountTotals(agg, byPerson);

        List<ProviderPayout> payouts = byPerson.values().stream()
                .map(m -> toPayout(m, config, tierGrantedProviderIds, feedbackByProvider, sc, year, month,
                        procedures.getOrDefault(m.providerId, new int[2]),
                        discounts.getOrDefault(m.providerId, ZERO_HALVES)))
                .sorted(Comparator.comparing(p -> p.name().toLowerCase())).toList();

        return new SettlementPreview(year, month, agg.timezone(), config, cutoff, payouts, agg.diagnostics());
    }

    /** Per-person service-line counts per half ({@code [first, second]}), for the #salary "procedures". */
    private static Map<Long, int[]> procedureCounts(MonthAggregation agg, Map<Long, Merged> byPerson) {
        Map<String, Long> personByMember = personByMember(byPerson);
        Map<Long, int[]> counts = new java.util.HashMap<>();
        for (var s : agg.services()) {
            Long pid = personByMember.get(s.providerId());
            if (pid == null) continue;
            int[] c = counts.computeIfAbsent(pid, k -> new int[2]);
            if ("FIRST".equals(s.half())) c[0]++; else c[1]++;
        }
        return counts;
    }

    /** Per-person discount totals per half ({@code [first, second]}) — the discounts the salon absorbed. */
    private static Map<Long, BigDecimal[]> discountTotals(MonthAggregation agg, Map<Long, Merged> byPerson) {
        Map<String, Long> personByMember = personByMember(byPerson);
        Map<Long, BigDecimal[]> sums = new java.util.HashMap<>();
        for (var s : agg.services()) {
            Long pid = personByMember.get(s.providerId());
            if (pid == null) continue;
            BigDecimal[] c = sums.computeIfAbsent(pid, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            if ("FIRST".equals(s.half())) c[0] = c[0].add(s.discount()); else c[1] = c[1].add(s.discount());
        }
        return sums;
    }

    private static Map<String, Long> personByMember(Map<Long, Merged> byPerson) {
        Map<String, Long> m = new java.util.HashMap<>();
        byPerson.forEach((pid, person) -> person.memberIds.forEach(mid -> m.put(mid, pid)));
        return m;
    }

    private static final BigDecimal[] ZERO_HALVES = {BigDecimal.ZERO, BigDecimal.ZERO};

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

    /**
     * Line-level trace for one provider/month (owner/manager drill-down): the provider's payout plus
     * every attributed service line (with discount, net, prepaid flag, channel), and the salon-wide
     * unattributed order lines — the suspects when a payment looks missing.
     */
    @Transactional
    public ProviderDetail providerDetail(int year, int month, Long providerId) {
        SalonConfig sc = salonConfig.findById(1)
                .orElseThrow(() -> new IllegalStateException("Salon config with id=1 is missing"));
        CommissionConfig config = sc.toCommissionConfig();

        Set<Long> granted = tierGrants.findByYearAndMonth(year, month).stream()
                .map(TierGrant::getProviderId).collect(Collectors.toSet());
        Map<Long, SettlementFeedback> fb = feedback.findByYearAndMonth(year, month).stream()
                .collect(Collectors.toMap(SettlementFeedback::getProviderId, f -> f, (a, b) -> a));

        MonthAggregation agg = aggregator.aggregate(year, month, sc.getServicePriceCutoff());
        Merged m = collapseToPersons(agg).get(providerId);
        if (m == null) {
            return new ProviderDetail(year, month, providerId, null, null, List.of(), agg.unmatched(), null, null);
        }
        List<SquareMonthAggregator.AttributedService> matched = agg.services().stream()
                .filter(s -> m.memberIds.contains(s.providerId()))
                // Chronological: oldest first, by appointment date then start time.
                .sorted(Comparator.comparing(SquareMonthAggregator.AttributedService::date)
                        .thenComparing(s -> parseTime(s.time()))
                        .thenComparing(SquareMonthAggregator.AttributedService::service))
                .toList();
        // Attach the short client name (e.g. "Donnah P.") — only this provider's customers, cached.
        Map<String, String> names = square.customerNames(matched.stream()
                .map(SquareMonthAggregator.AttributedService::customerId).toList());
        List<SquareMonthAggregator.AttributedService> lines = matched.stream()
                .map(s -> s.withCustomer(shortName(names.get(s.customerId()))))
                .toList();
        int firstCount = (int) lines.stream().filter(s -> "FIRST".equals(s.half())).count();
        int secondCount = (int) lines.stream().filter(s -> "SECOND".equals(s.half())).count();
        BigDecimal firstDisc = lines.stream().filter(s -> "FIRST".equals(s.half()))
                .map(SquareMonthAggregator.AttributedService::discount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal secondDisc = lines.stream().filter(s -> "SECOND".equals(s.half()))
                .map(SquareMonthAggregator.AttributedService::discount).reduce(BigDecimal.ZERO, BigDecimal::add);
        ProviderPayout payout = toPayout(m, config, granted, fb, sc, year, month,
                new int[]{firstCount, secondCount}, new BigDecimal[]{firstDisc, secondDisc});
        return new ProviderDetail(year, month, providerId, m.name, payout, lines, agg.unmatched(),
                payout.firstHalfMessage(), payout.secondHalfMessage());
    }

    /** The copy-pasteable {@code #salary} block for one half, matching the salon's manual format. */
    private static String salaryMessage(int year, int month, Half half, String providerName,
                                        SalonConfig sc, HalfInput input, HalfSettlement settlement,
                                        int procedures, BigDecimal discountsCovered) {
        String label = (half == Half.FIRST ? "1-15 " : "16-END ")
                + java.time.Month.of(month).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.US)
                + " " + year;
        String feePct = sc.getCardTipFeeRate().multiply(BigDecimal.valueOf(100))
                .setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        String owner = sc.getOwnerShortName();
        // Discounts are absorbed by the salon and already included in Card (we pay on gross), so they
        // are shown here for transparency, not added again. The manual adjustments line (redos/hours)
        // only appears when non-zero.
        String adjustments = input.adjustments().signum() != 0
                ? "Adjustments (cancellations, hours, redos): $" + money(input.adjustments()) + "\n" : "";
        return "#salary " + label + "\n"
                + procedures + " procedures\n"
                + "Card: $" + money(input.cardRevenue()) + "\n"
                + "Cash: $" + money(input.cashTotal()) + "\n\n"
                + "Discounts covered by salon: $" + money(discountsCovered) + "\n"
                + adjustments
                + "Tips: $" + money(input.cardTips()) + "\n"
                + "Tips(-" + feePct + "%): $" + money(settlement.tipsAfterFee()) + "\n\n"
                + "Zelle " + owner + " to " + providerName + ": $" + money(settlement.zelleToProvider()) + "\n"
                + "Cash from " + providerName + " to " + owner + ": $" + money(settlement.cashToSalon());
    }

    private static String money(BigDecimal amount) {
        return amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static final java.time.format.DateTimeFormatter TIME_PARSE =
            java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US);

    /** Parse the display time ("2:30 PM") for chronological sorting; unknown sorts first. */
    private static java.time.LocalTime parseTime(String t) {
        if (t == null || t.isBlank()) return java.time.LocalTime.MIN;
        try {
            return java.time.LocalTime.parse(t, TIME_PARSE);
        } catch (RuntimeException e) {
            return java.time.LocalTime.MIN;
        }
    }

    /** "Donnah Phipps" → "Donnah P." (first name + last initial); null/blank → null. */
    private static String shortName(String full) {
        if (full == null || full.isBlank()) return null;
        String[] parts = full.trim().split("\\s+");
        if (parts.length == 1) return parts[0];
        String last = parts[parts.length - 1];
        return parts[0] + " " + Character.toUpperCase(last.charAt(0)) + ".";
    }

    /** Collapse Square team members into provider persons (merging any duplicate accounts). */
    private Map<Long, Merged> collapseToPersons(MonthAggregation agg) {
        Map<Long, Merged> byPerson = new LinkedHashMap<>();
        for (var pm : agg.providers()) {
            Provider person = directory.resolveOrCreate(pm.providerId(), pm.name());
            Merged m = byPerson.computeIfAbsent(person.getId(),
                    k -> new Merged(person.getId(), person.getDisplayName()));
            m.first = sum(m.first, pm.firstHalf());
            m.second = sum(m.second, pm.secondHalf());
            m.memberIds.add(pm.providerId());
        }
        return byPerson;
    }

    private ProviderPayout toPayout(Merged m, CommissionConfig config, Set<Long> granted,
                                    Map<Long, SettlementFeedback> feedbackByProvider,
                                    SalonConfig sc, int year, int month, int[] procedures,
                                    BigDecimal[] discounts) {
        int monthCounted = m.first.countedServices() + m.second.countedServices();
        boolean autoQualified = monthCounted >= config.tierServiceThreshold();
        boolean isGranted = granted.contains(m.providerId);
        Boolean tierGrant = isGranted ? Boolean.TRUE : null;

        HalfSettlement first = engine.firstHalf(m.first, config);
        HalfSettlement second = engine.secondHalfFinal(m.first, m.second, config, tierGrant);
        BigDecimal monthZelle = first.zelleToProvider().add(second.zelleToProvider());
        BigDecimal monthCashToSalon = first.cashToSalon().add(second.cashToSalon());
        SettlementFeedback fb = feedbackByProvider.get(m.providerId);
        String firstMsg = salaryMessage(year, month, Half.FIRST, m.name, sc, m.first, first, procedures[0], discounts[0]);
        String secondMsg = salaryMessage(year, month, Half.SECOND, m.name, sc, m.second, second, procedures[1], discounts[1]);
        return new ProviderPayout(m.providerId, m.name, monthCounted, autoQualified,
                isGranted && !autoQualified, autoQualified || isGranted,
                first, second, monthZelle, monthCashToSalon,
                fb != null ? fb.getStatus().name() : null,
                fb != null ? fb.getComment() : null,
                firstMsg, secondMsg);
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
        final Set<String> memberIds = new java.util.HashSet<>();
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
                                 String feedbackStatus, String feedbackComment,
                                 String firstHalfMessage, String secondHalfMessage) {}

    /**
     * Line-level trace for one provider/month, plus the salon-wide unattributed lines and the
     * copy-pasteable {@code #salary} block for each half.
     */
    public record ProviderDetail(int year, int month, Long providerId, String name,
                                 ProviderPayout payout,
                                 List<SquareMonthAggregator.AttributedService> services,
                                 List<SquareMonthAggregator.UnmatchedLine> unmatched,
                                 String firstHalfMessage, String secondHalfMessage) {}
}
