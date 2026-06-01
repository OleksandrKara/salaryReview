package com.salonreview.square;

import com.salonreview.commission.CommissionConfig;
import com.salonreview.commission.HalfInput;
import com.salonreview.commission.HalfSettlement;
import com.salonreview.commission.TierCommissionEngine;
import com.salonreview.domain.Half;
import com.salonreview.domain.Provider;
import com.salonreview.domain.SalonConfig;
import com.salonreview.domain.PrepaidPackage;
import com.salonreview.domain.PrepaidRedemption;
import com.salonreview.domain.SettlementFeedback;
import com.salonreview.domain.TierGrant;
import com.salonreview.repo.PrepaidPackageRepository;
import com.salonreview.repo.PrepaidRedemptionRepository;
import com.salonreview.repo.SalonConfigRepository;
import com.salonreview.repo.SettlementFeedbackRepository;
import com.salonreview.repo.TierGrantRepository;
import com.salonreview.service.ProviderDirectory;
import com.salonreview.square.SquareMonthAggregator.AttributedService;
import com.salonreview.square.SquareMonthAggregator.MonthAggregation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
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
    private final PrepaidRedemptionRepository prepaidRedemptions;
    private final PrepaidPackageRepository prepaidPackages;
    private final com.salonreview.repo.ProviderRepository providerRepo;

    public SettlementPreviewService(SquareMonthAggregator aggregator, TierCommissionEngine engine,
                                    SalonConfigRepository salonConfig, ProviderDirectory directory,
                                    TierGrantRepository tierGrants, SettlementFeedbackRepository feedback,
                                    SquareClient square, PrepaidRedemptionRepository prepaidRedemptions,
                                    PrepaidPackageRepository prepaidPackages,
                                    com.salonreview.repo.ProviderRepository providerRepo) {
        this.aggregator = aggregator;
        this.engine = engine;
        this.salonConfig = salonConfig;
        this.directory = directory;
        this.tierGrants = tierGrants;
        this.feedback = feedback;
        this.square = square;
        this.prepaidRedemptions = prepaidRedemptions;
        this.prepaidPackages = prepaidPackages;
        this.providerRepo = providerRepo;
    }

    /**
     * Confirmed prepaid draw-downs whose service date falls in the month, as synthetic attributed
     * service lines keyed by provider id. These pay the provider on the service date exactly like a
     * card service (channel {@code PREPAID}); folded into the half inputs, procedures and the trace.
     */
    private Map<Long, List<AttributedService>> prepaidLinesByProvider(int year, int month) {
        YearMonth ym = YearMonth.of(year, month);
        List<PrepaidRedemption> rs = prepaidRedemptions.findByServiceDateBetween(ym.atDay(1), ym.atEndOfMonth());
        if (rs.isEmpty()) return Map.of();
        Map<Long, PrepaidPackage> pkgById = prepaidPackages.findAllById(
                rs.stream().map(PrepaidRedemption::getPackageId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(PrepaidPackage::getId, p -> p));
        Map<Long, List<AttributedService>> byProvider = new LinkedHashMap<>();
        for (PrepaidRedemption r : rs) {
            PrepaidPackage pkg = pkgById.get(r.getPackageId());
            if (pkg == null) continue;
            String half = r.getServiceDate().getDayOfMonth() <= 15 ? "FIRST" : "SECOND";
            BigDecimal price = r.getMenuPrice(); // gross — the menu price the provider is paid on
            // The prepaid invoice was often discounted (e.g. "Prepay for 3 sessions" −10%). Surface that
            // per service: discount = menu − what was actually paid per session (package amount / count),
            // so the breakdown + #salary show it (salon absorbs it; the payout is still on the menu price,
            // exactly like card/cash discounts).
            BigDecimal perSession = pkg.getTotalServices() > 0
                    ? pkg.getAmount().divide(BigDecimal.valueOf(pkg.getTotalServices()), 2, java.math.RoundingMode.HALF_UP)
                    : price;
            BigDecimal discount = price.subtract(perSession).max(BigDecimal.ZERO);
            BigDecimal net = price.subtract(discount);
            // Credit the provider who performed THIS draw-down (a package can span several providers).
            String providerName = providerRepo.findById(r.getProviderId())
                    .map(com.salonreview.domain.Provider::getDisplayName).orElse("");
            byProvider.computeIfAbsent(r.getProviderId(), k -> new ArrayList<>())
                    .add(new AttributedService("", providerName, r.getServiceDate().toString(),
                            half, r.getServiceName() == null ? "Prepaid service" : r.getServiceName(),
                            price, discount, net, BigDecimal.ZERO, r.isCounts(), r.isCounts() ? 1 : 0, 1, false,
                            "PREPAID", null, r.getSquareBookingId(), pkg.getCustomerId(), pkg.getCustomerName()));
        }
        return byProvider;
    }

    /** Fold each provider's prepaid lines into their half inputs + the #salary procedure & discount totals. */
    private static void applyPrepaid(Map<Long, Merged> byPerson, Map<Long, int[]> procedures,
                                     Map<Long, BigDecimal[]> discounts, Map<Long, List<AttributedService>> prepaid) {
        prepaid.forEach((providerId, lines) -> {
            // A provider may have only prepaid activity this month (no Square orders) — still pay them,
            // so create their bucket if missing (name taken from the resolved prepaid line).
            Merged m = byPerson.computeIfAbsent(providerId,
                    k -> new Merged(k, lines.isEmpty() ? "" : lines.get(0).providerName()));
            applyPrepaidToMerged(m, lines);
            int[] proc = procedures.computeIfAbsent(providerId, k -> new int[2]);
            BigDecimal[] disc = discounts.computeIfAbsent(providerId, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            for (AttributedService l : lines) {
                int h = "FIRST".equals(l.half()) ? 0 : 1;
                proc[h] += l.countedUnits();
                disc[h] = disc[h].add(l.discount()); // prepaid discount → "Discounts covered by salon"
            }
        });
    }

    /** Add prepaid revenue (gross, like card) and counted services to a person's half inputs. */
    private static void applyPrepaidToMerged(Merged m, List<AttributedService> lines) {
        if (lines == null) return;
        for (AttributedService l : lines) {
            if ("FIRST".equals(l.half())) m.first = addCardAndCount(m.first, l.gross(), l.countedUnits());
            else m.second = addCardAndCount(m.second, l.gross(), l.countedUnits());
        }
    }

    private static HalfInput addCardAndCount(HalfInput h, BigDecimal card, int counted) {
        return new HalfInput(h.countedServices() + counted, h.cardRevenue().add(card), h.cardTips(),
                h.cashGross(), h.cashCollected(), h.adjustments());
    }

    @Transactional
    public SettlementPreview preview(int year, int month) {
        SalonConfig sc = salonConfig.findById(1)
                .orElseThrow(() -> new IllegalStateException("Salon config with id=1 is missing"));
        CommissionConfig config = sc.toCommissionConfig();
        BigDecimal cutoff = sc.getServicePriceCutoff();

        Set<Long> tierGrantedProviderIds = tierGrants.findByYearAndMonth(year, month).stream()
                .map(TierGrant::getProviderId).collect(Collectors.toSet());

        Map<Long, Map<Half, SettlementFeedback>> feedbackByProvider = feedback.findByYearAndMonth(year, month).stream()
                .collect(Collectors.groupingBy(SettlementFeedback::getProviderId,
                        Collectors.toMap(SettlementFeedback::getHalf, f -> f, (a, b) -> a)));

        MonthAggregation agg = aggregator.aggregate(year, month, cutoff);
        Map<Long, Merged> byPerson = collapseToPersons(agg);
        Map<Long, int[]> procedures = procedureCounts(agg, byPerson);
        Map<Long, BigDecimal[]> discounts = discountTotals(agg, byPerson);

        // Fold confirmed prepaid draw-downs into the same provider/half buckets (revenue + counts +
        // procedures), so they pay out and show in #salary exactly like a card service.
        Map<Long, List<AttributedService>> prepaid = prepaidLinesByProvider(year, month);
        applyPrepaid(byPerson, procedures, discounts, prepaid);

        List<ProviderPayout> payouts = byPerson.values().stream()
                .map(m -> toPayout(m, config, tierGrantedProviderIds, feedbackByProvider, sc, year, month,
                        procedures.getOrDefault(m.providerId, new int[2]),
                        discounts.getOrDefault(m.providerId, ZERO_HALVES)))
                .sorted(Comparator.comparing(p -> p.name().toLowerCase())).toList();

        return new SettlementPreview(year, month, agg.timezone(), config, cutoff, payouts, agg.diagnostics(),
                java.time.Instant.now().toString());
    }

    /**
     * Per-person <em>main</em>-service counts per half ({@code [first, second]}), for the #salary
     * "procedures" line. Counts main services (gross &ge; the tier cutoff) via {@code countedUnits},
     * not raw line count — add-ons below the cutoff and the non-counted part of a multi-service cash
     * note don't inflate it, so the number matches the provider's tier count everywhere else.
     */
    private static Map<Long, int[]> procedureCounts(MonthAggregation agg, Map<Long, Merged> byPerson) {
        Map<String, Long> personByMember = personByMember(byPerson);
        Map<Long, int[]> counts = new java.util.HashMap<>();
        for (var s : agg.services()) {
            Long pid = personByMember.get(s.providerId());
            if (pid == null) continue;
            int[] c = counts.computeIfAbsent(pid, k -> new int[2]);
            if ("FIRST".equals(s.half())) c[0] += s.countedUnits(); else c[1] += s.countedUnits();
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
        Map<Long, Map<Half, SettlementFeedback>> fb = feedback.findByYearAndMonth(year, month).stream()
                .collect(Collectors.groupingBy(SettlementFeedback::getProviderId,
                        Collectors.toMap(SettlementFeedback::getHalf, f -> f, (a, b) -> a)));

        MonthAggregation agg = aggregator.aggregate(year, month, sc.getServicePriceCutoff());
        Map<Long, List<AttributedService>> prepaid = prepaidLinesByProvider(year, month);
        Merged m = collapseToPersons(agg).get(providerId);
        if (m == null) {
            // No Square activity this month — but the provider may still have prepaid draw-downs to show.
            List<AttributedService> pl = prepaid.get(providerId);
            if (pl == null || pl.isEmpty()) {
                return new ProviderDetail(year, month, providerId, null, null, List.of(), agg.unmatched(), null, null,
                        sc.getServicePriceCutoff(), agg.timezone(), java.time.Instant.now().toString());
            }
            m = new Merged(providerId, pl.get(0).providerName());
        }
        final Merged person = m; // effectively-final alias for the lambda below
        List<SquareMonthAggregator.AttributedService> matched = agg.services().stream()
                .filter(s -> person.memberIds.contains(s.providerId()))
                // Chronological: oldest first, by appointment date then start time.
                .sorted(Comparator.comparing(SquareMonthAggregator.AttributedService::date)
                        .thenComparing(s -> parseTime(s.time()))
                        .thenComparing(SquareMonthAggregator.AttributedService::service))
                .toList();
        // Attach the short client name (e.g. "Donnah P.") — only this provider's customers, cached.
        Map<String, String> names = square.customerNames(matched.stream()
                .map(SquareMonthAggregator.AttributedService::customerId).toList());
        // Prepaid draw-downs for this provider this month: fold into the half input (pays out) and
        // into the trace lines, then re-sort chronologically.
        applyPrepaidToMerged(m, prepaid.get(providerId));

        List<AttributedService> lines = new ArrayList<>(matched.stream()
                .map(s -> s.withCustomer(shortName(names.get(s.customerId()))))
                .toList());
        if (prepaid.containsKey(providerId)) lines.addAll(prepaid.get(providerId));
        lines = lines.stream()
                .sorted(Comparator.comparing(AttributedService::date)
                        .thenComparing(s -> parseTime(s.time()))
                        .thenComparing(AttributedService::service))
                .toList();
        // #salary "procedures" = main services (gross >= cutoff), via countedUnits — not raw line count.
        int firstCount = lines.stream().filter(s -> "FIRST".equals(s.half()))
                .mapToInt(AttributedService::countedUnits).sum();
        int secondCount = lines.stream().filter(s -> "SECOND".equals(s.half()))
                .mapToInt(AttributedService::countedUnits).sum();
        BigDecimal firstDisc = lines.stream().filter(s -> "FIRST".equals(s.half()))
                .map(AttributedService::discount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal secondDisc = lines.stream().filter(s -> "SECOND".equals(s.half()))
                .map(AttributedService::discount).reduce(BigDecimal.ZERO, BigDecimal::add);
        ProviderPayout payout = toPayout(m, config, granted, fb, sc, year, month,
                new int[]{firstCount, secondCount}, new BigDecimal[]{firstDisc, secondDisc});
        return new ProviderDetail(year, month, providerId, m.name, payout, lines, agg.unmatched(),
                payout.firstHalfMessage(), payout.secondHalfMessage(), sc.getServicePriceCutoff(),
                agg.timezone(), java.time.Instant.now().toString());
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
        // 16-END only: at month close a qualified provider (50/50) earns a tier bonus on the month's
        // card (and a cash rebate). It's already inside the Zelle/Cash totals below — shown here so the
        // 50/50 uplift is explicit. Only appears when there's a bonus (i.e. the 50/50 tier applied).
        String bonus = (half == Half.SECOND && settlement.tierBonus().signum() > 0)
                ? "50/50 bonus (in Zelle): $" + money(settlement.tierBonus()) + "\n"
                + (settlement.cashTierRebate().signum() > 0
                        ? "50/50 cash rebate (off cash to " + owner + "): $" + money(settlement.cashTierRebate()) + "\n" : "")
                : "";
        return "#salary " + label + "\n"
                + procedures + " procedures\n"
                + "Card: $" + money(input.cardRevenue()) + "\n"
                + "Cash: $" + money(input.cashCollected()) + "\n\n"
                + "Discounts covered by salon: $" + money(discountsCovered) + "\n"
                + adjustments
                + "Tips: $" + money(input.cardTips()) + "\n"
                + "Tips(-" + feePct + "%): $" + money(settlement.tipsAfterFee()) + "\n\n"
                + bonus
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
                                    Map<Long, Map<Half, SettlementFeedback>> feedbackByProvider,
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
        Map<Half, SettlementFeedback> fbm = feedbackByProvider.getOrDefault(m.providerId, Map.of());
        String firstMsg = salaryMessage(year, month, Half.FIRST, m.name, sc, m.first, first, procedures[0], discounts[0]);
        String secondMsg = salaryMessage(year, month, Half.SECOND, m.name, sc, m.second, second, procedures[1], discounts[1]);
        return new ProviderPayout(m.providerId, m.name, monthCounted, autoQualified,
                isGranted && !autoQualified, autoQualified || isGranted,
                first, second, monthZelle, monthCashToSalon,
                toFeedback(fbm.get(Half.FIRST)), toFeedback(fbm.get(Half.SECOND)),
                firstMsg, secondMsg);
    }

    private static Feedback toFeedback(SettlementFeedback f) {
        return f == null ? null : new Feedback(f.getStatus().name(), f.getComment());
    }

    private static HalfInput sum(HalfInput a, HalfInput b) {
        return new HalfInput(
                a.countedServices() + b.countedServices(),
                a.cardRevenue().add(b.cardRevenue()),
                a.cardTips().add(b.cardTips()),
                a.cashGross().add(b.cashGross()),
                a.cashCollected().add(b.cashCollected()),
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
                                    SquareMonthAggregator.Diag diagnostics, String syncedAt) {}

    public record ProviderPayout(Long providerId, String name, int monthCountedServices,
                                 boolean autoQualified, boolean tierManuallyGranted, boolean tierApplied,
                                 HalfSettlement firstHalf, HalfSettlement secondHalf,
                                 BigDecimal monthZelleToProvider, BigDecimal monthCashToSalon,
                                 Feedback firstFeedback, Feedback secondFeedback,
                                 String firstHalfMessage, String secondHalfMessage) {}

    /** A provider's response to one period: {@code status} = APPROVED / CHANGES_REQUESTED, + comment. */
    public record Feedback(String status, String comment) {}

    /**
     * Line-level trace for one provider/month, plus the salon-wide unattributed lines and the
     * copy-pasteable {@code #salary} block for each half.
     */
    public record ProviderDetail(int year, int month, Long providerId, String name,
                                 ProviderPayout payout,
                                 List<SquareMonthAggregator.AttributedService> services,
                                 List<SquareMonthAggregator.UnmatchedLine> unmatched,
                                 String firstHalfMessage, String secondHalfMessage,
                                 BigDecimal priceCutoff, String timezone, String syncedAt) {}
}
