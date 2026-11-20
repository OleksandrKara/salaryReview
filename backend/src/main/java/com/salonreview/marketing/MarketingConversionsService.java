package com.salonreview.marketing;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.square.SquareClientProvider;
import com.salonreview.web.dto.MarketingConversionsDto;
import com.salonreview.web.dto.GooglePpcDto;
import com.salonreview.web.dto.MarketingConversionsDto.SourceRow;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Counts each kind of conversion on a landing page, split by traffic channel: text and call taps,
 * consultations booked, deposits and full payments. Payments come from the Square mirror, matched
 * to the people who left their details on the page, and only payments made after they did so count.
 * Also totals the Google Ads results across every page of the business for the Google PPC tab.
 */
@Service
public class MarketingConversionsService {

    /** The studio's deposit is $100; the in-person consultation fee ($50) stays below it. */
    static final BigDecimal DEPOSIT_MIN = new BigDecimal("100");
    /** A customer who has paid this much in total has paid for the procedure, not just a deposit. */
    static final BigDecimal FULL_MIN_TOTAL = new BigDecimal("300");

    private static final List<String> CHANNELS = List.of(
            "meta_ads", "google_ads", "instagram_organic", "google_organic", "yelp", "direct", "other");
    private static final String GOOGLE = "google_ads";

    private final MarketingDashboardRepository dashboardRepository;
    private final MarketingConversionsRepository repository;
    private final SquareClientProvider squareClientProvider;
    private final CurrentBusinessContext businessContext;

    public MarketingConversionsService(MarketingDashboardRepository dashboardRepository,
                                       MarketingConversionsRepository repository,
                                       SquareClientProvider squareClientProvider,
                                       CurrentBusinessContext businessContext) {
        this.dashboardRepository = dashboardRepository;
        this.repository = repository;
        this.squareClientProvider = squareClientProvider;
        this.businessContext = businessContext;
    }

    private static final class Agg {
        long views, sms, calls, consultations, deposits, full, paying;
        BigDecimal revenue = BigDecimal.ZERO;
    }

    private record PageResult(Map<String, Agg> agg, Map<String, BigDecimal> googlePaid, Set<String> googleDeposit) {}

    public MarketingConversionsDto conversions(String slugParam, LocalDate periodFrom, LocalDate periodTo) {
        try {
            Long businessId = businessContext.id();
            String slug = slugParam != null ? slugParam : dashboardRepository.findDefaultSlugForBusiness(businessId).orElse(null);
            if (slug == null) return MarketingConversionsDto.unavailable(slugParam);
            Optional<UUID> pageId = dashboardRepository.findLandingPageId(slug, businessId);
            if (pageId.isEmpty()) return MarketingConversionsDto.unavailable(slug);

            ZoneId zone = resolveZone();
            PageResult r = compute(businessId, slug, pageId.get(), periodFrom, periodTo, zone);
            long views = 0;
            List<SourceRow> bySource = new ArrayList<>();
            for (String ch : CHANNELS) {
                Agg a = r.agg().get(ch);
                views += a.views;
                bySource.add(toRow(ch, a));
            }
            return new MarketingConversionsDto(true, slug, views, bySource);
        } catch (DataAccessException e) {
            return MarketingConversionsDto.unavailable(slugParam);
        }
    }

    public GooglePpcDto googlePpc(LocalDate periodFrom, LocalDate periodTo) {
        Long businessId = businessContext.id();
        ZoneId zone = resolveZone();
        Agg total = new Agg();
        Map<String, BigDecimal> paid = new HashMap<>();
        Set<String> deposit = new HashSet<>();
        List<GooglePpcDto.PageResults> pages = new ArrayList<>();
        for (var lp : dashboardRepository.listLandingPages(businessId)) {
            Optional<UUID> id = dashboardRepository.findLandingPageId(lp.slug(), businessId);
            if (id.isEmpty()) continue;
            PageResult r = compute(businessId, lp.slug(), id.get(), periodFrom, periodTo, zone);
            Agg g = r.agg().get(GOOGLE);
            total.views += g.views; total.sms += g.sms; total.calls += g.calls; total.consultations += g.consultations;
            r.googlePaid().forEach((c, amt) -> paid.merge(c, amt, BigDecimal::max));
            deposit.addAll(r.googleDeposit());
            if (g.views > 0 || g.consultations > 0 || g.revenue.signum() > 0) {
                pages.add(new GooglePpcDto.PageResults(lp.slug(), lp.name(), g.views, g.sms, g.calls,
                        g.consultations, g.deposits, g.full, g.revenue));
            }
        }
        BigDecimal revenue = BigDecimal.ZERO;
        long full = 0;
        for (var e : paid.entrySet()) {
            revenue = revenue.add(e.getValue());
            if (deposit.contains(e.getKey()) && e.getValue().compareTo(FULL_MIN_TOTAL) >= 0) full++;
        }
        var results = new GooglePpcDto.Results(total.views, total.sms, total.calls, total.consultations,
                deposit.size(), full, paid.size(), revenue);

        LocalDate from = periodFrom;
        var campaigns = repository.findGoogleAdsCampaigns(businessId, from, periodTo);
        long cost = 0, imps = 0, clicks = 0;
        double conv = 0;
        List<GooglePpcDto.Campaign> out = new ArrayList<>();
        for (var c : campaigns) {
            cost += c.costMicros(); imps += c.impressions(); clicks += c.clicks(); conv += c.conversions();
            out.add(new GooglePpcDto.Campaign(c.campaignId(), c.name(), c.status(), micros(c.costMicros()),
                    c.impressions(), c.clicks(), c.conversions()));
        }
        return new GooglePpcDto(!campaigns.isEmpty(), micros(cost), imps, clicks, conv, results, out, pages);
    }

    private PageResult compute(Long businessId, String slug, UUID pageId, LocalDate periodFrom, LocalDate periodTo, ZoneId zone) {
        Instant periodFromI = periodFrom == null ? null : periodFrom.atStartOfDay(zone).toInstant();
        Instant to = periodTo == null ? null : periodTo.plusDays(1).atStartOfDay(zone).toInstant();
        Instant statsSince = dashboardRepository.findStatsSince(pageId).orElse(null);
        Instant from = statsSince == null ? periodFromI
                : periodFromI == null ? statsSince
                : statsSince.isAfter(periodFromI) ? statsSince : periodFromI;

        Map<String, Agg> agg = new HashMap<>();
        for (String ch : CHANNELS) agg.put(ch, new Agg());
        repository.countVisitorsByChannel(pageId, from, to).forEach((ch, kinds) -> {
            Agg a = agg.getOrDefault(ch, agg.get("other"));
            a.views += kinds.getOrDefault("views", 0L);
            a.sms += kinds.getOrDefault("sms", 0L);
            a.calls += kinds.getOrDefault("call", 0L);
        });

        Map<String, Instant> firstSeen = new HashMap<>();
        Map<String, String> customerChannel = new HashMap<>();
        for (var c : repository.findContacts(businessId, slug, from, to)) {
            Agg a = agg.getOrDefault(c.channel(), agg.get("other"));
            if (c.bookingId() != null) a.consultations++;
            if (c.customerId() != null) {
                Instant seen = firstSeen.get(c.customerId());
                if (seen == null || c.createdAt().isBefore(seen)) {
                    firstSeen.put(c.customerId(), c.createdAt());
                    customerChannel.put(c.customerId(), agg.containsKey(c.channel()) ? c.channel() : "other");
                }
            }
        }
        Map<String, BigDecimal> paidTotal = new HashMap<>();
        Set<String> deposit = new HashSet<>();
        for (var p : repository.findPayments(businessId, firstSeen.keySet())) {
            if (p.createdAt().isBefore(firstSeen.get(p.customerId()))) continue;
            paidTotal.merge(p.customerId(), p.amount(), BigDecimal::add);
            if (p.amount().compareTo(DEPOSIT_MIN) >= 0) deposit.add(p.customerId());
        }
        Map<String, BigDecimal> googlePaid = new HashMap<>();
        Set<String> googleDeposit = new HashSet<>();
        for (var e : paidTotal.entrySet()) {
            String ch = customerChannel.get(e.getKey());
            Agg a = agg.get(ch);
            a.paying++;
            a.revenue = a.revenue.add(e.getValue());
            boolean dep = deposit.contains(e.getKey());
            if (dep) {
                a.deposits++;
                if (e.getValue().compareTo(FULL_MIN_TOTAL) >= 0) a.full++;
            }
            if (GOOGLE.equals(ch)) {
                googlePaid.put(e.getKey(), e.getValue());
                if (dep) googleDeposit.add(e.getKey());
            }
        }
        return new PageResult(agg, googlePaid, googleDeposit);
    }

    private static SourceRow toRow(String ch, Agg a) {
        return new SourceRow(ch, a.views, a.sms, a.calls, a.consultations, a.deposits, a.full, a.paying, a.revenue);
    }

    private static BigDecimal micros(long m) {
        return BigDecimal.valueOf(m).divide(BigDecimal.valueOf(1_000_000), 2, java.math.RoundingMode.HALF_UP);
    }

    private ZoneId resolveZone() {
        try {
            String tz = squareClientProvider.forBusiness(businessContext.id()).locationTimeZone();
            return tz != null && !tz.isBlank() ? ZoneId.of(tz) : ZoneOffset.UTC;
        } catch (RuntimeException e) {
            return ZoneOffset.UTC;
        }
    }
}
