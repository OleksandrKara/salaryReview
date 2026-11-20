package com.salonreview.marketing;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.square.SquareClientProvider;
import com.salonreview.web.dto.MarketingConversionsDto;
import com.salonreview.web.dto.MarketingConversionsDto.Campaign;
import com.salonreview.web.dto.MarketingConversionsDto.GoogleAds;
import com.salonreview.web.dto.MarketingConversionsDto.Row;
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
 * Counts each kind of conversion on one landing page and weights them: a text or call tap is
 * worth less than a booked consultation, which is worth less than a paid deposit, which is worth
 * less than a full payment. Payments come from the Square mirror, matched to the people who left
 * their details on the page, and only payments made after they did so count.
 */
@Service
public class MarketingConversionsService {

    static final int W_SMS = 1;
    static final int W_CALL = 1;
    static final int W_CONSULTATION = 5;
    static final int W_DEPOSIT = 20;
    static final int W_FULL = 50;

    /** The studio's deposit is $100; the in-person consultation fee ($50) stays below it. */
    static final BigDecimal DEPOSIT_MIN = new BigDecimal("100");
    /** A customer who has paid this much in total has paid for the procedure, not just a deposit. */
    static final BigDecimal FULL_MIN_TOTAL = new BigDecimal("300");

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

    private static final List<String> CHANNELS = List.of(
            "meta_ads", "google_ads", "instagram_organic", "google_organic", "yelp", "direct", "other");

    private static final class Agg {
        long views, sms, calls, consultations, deposits, full, paying;
        BigDecimal revenue = BigDecimal.ZERO;
    }

    public MarketingConversionsDto conversions(String slugParam, LocalDate periodFrom, LocalDate periodTo, Set<String> sources) {
        try {
            Long businessId = businessContext.id();
            String slug = slugParam != null ? slugParam : dashboardRepository.findDefaultSlugForBusiness(businessId).orElse(null);
            if (slug == null) return MarketingConversionsDto.unavailable(slugParam);
            Optional<UUID> pageId = dashboardRepository.findLandingPageId(slug, businessId);
            if (pageId.isEmpty()) return MarketingConversionsDto.unavailable(slug);

            ZoneId zone = resolveZone();
            Instant periodFromI = periodFrom == null ? null : periodFrom.atStartOfDay(zone).toInstant();
            Instant to = periodTo == null ? null : periodTo.plusDays(1).atStartOfDay(zone).toInstant();
            Instant statsSince = dashboardRepository.findStatsSince(pageId.get()).orElse(null);
            Instant from = statsSince == null ? periodFromI
                    : periodFromI == null ? statsSince
                    : statsSince.isAfter(periodFromI) ? statsSince : periodFromI;

            Map<String, Agg> agg = new HashMap<>();
            for (String ch : CHANNELS) agg.put(ch, new Agg());
            repository.countVisitorsByChannel(pageId.get(), from, to).forEach((ch, kinds) -> {
                Agg a = agg.getOrDefault(ch, agg.get("other"));
                a.views += kinds.getOrDefault("views", 0L);
                a.sms += kinds.getOrDefault("sms", 0L);
                a.calls += kinds.getOrDefault("call", 0L);
            });

            List<MarketingConversionsRepository.ContactRow> contacts = repository.findContacts(businessId, slug, from, to);
            Map<String, Instant> firstSeen = new HashMap<>();
            Map<String, String> customerChannel = new HashMap<>();
            for (var c : contacts) {
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
            for (var e : paidTotal.entrySet()) {
                Agg a = agg.get(customerChannel.get(e.getKey()));
                a.paying++;
                a.revenue = a.revenue.add(e.getValue());
                if (deposit.contains(e.getKey())) {
                    a.deposits++;
                    if (e.getValue().compareTo(FULL_MIN_TOTAL) >= 0) a.full++;
                }
            }

            List<SourceRow> bySource = new ArrayList<>();
            Agg sel = new Agg();
            for (String ch : CHANNELS) {
                Agg a = agg.get(ch);
                long pts = a.sms * W_SMS + a.calls * W_CALL + a.consultations * W_CONSULTATION + a.deposits * W_DEPOSIT + a.full * W_FULL;
                bySource.add(new SourceRow(ch, a.views, a.sms, a.calls, a.consultations, a.deposits, a.full, a.paying, a.revenue, pts));
                boolean selected = sources.contains(ch) || ("other".equals(ch) && sources.equals(TrafficSourceSql.ALL));
                if (selected) {
                    sel.views += a.views; sel.sms += a.sms; sel.calls += a.calls; sel.consultations += a.consultations;
                    sel.deposits += a.deposits; sel.full += a.full; sel.paying += a.paying; sel.revenue = sel.revenue.add(a.revenue);
                }
            }

            List<Row> rows = new ArrayList<>();
            rows.add(row("sms", "Text message tap", "Visitors who tapped a text link", W_SMS, sel.sms));
            rows.add(row("call", "Call tap", "Visitors who tapped a phone link", W_CALL, sel.calls));
            rows.add(row("consultation", "Consultation booked", "People who booked a consultation here", W_CONSULTATION, sel.consultations));
            rows.add(row("deposit", "Deposit paid", "Paid $100 or more in Square after leaving details", W_DEPOSIT, sel.deposits));
            rows.add(row("full", "Full payment", "Paid $300 or more in total (deposit plus procedure)", W_FULL, sel.full));
            long total = rows.stream().mapToLong(Row::points).sum();
            return new MarketingConversionsDto(true, slug, sel.views, rows, total, sel.paying, sel.revenue,
                    bySource, googleAds(businessId, periodFrom, periodTo, statsSince, zone));
        } catch (DataAccessException e) {
            return MarketingConversionsDto.unavailable(slugParam);
        }
    }

    private GoogleAds googleAds(Long businessId, LocalDate periodFrom, LocalDate periodTo, Instant statsSince, ZoneId zone) {
        LocalDate from = periodFrom;
        if (statsSince != null) {
            LocalDate since = statsSince.atZone(zone).toLocalDate();
            if (from == null || since.isAfter(from)) from = since;
        }
        var campaigns = repository.findGoogleAdsCampaigns(businessId, from, periodTo);
        if (campaigns.isEmpty()) return null;
        long cost = 0, imps = 0, clicks = 0;
        double conv = 0;
        List<Campaign> out = new ArrayList<>();
        for (var c : campaigns) {
            cost += c.costMicros(); imps += c.impressions(); clicks += c.clicks(); conv += c.conversions();
            out.add(new Campaign(c.campaignId(), c.name(), c.status(), micros(c.costMicros()), c.impressions(), c.clicks(), c.conversions()));
        }
        return new GoogleAds(micros(cost), imps, clicks, conv, out);
    }

    private static BigDecimal micros(long m) {
        return BigDecimal.valueOf(m).divide(BigDecimal.valueOf(1_000_000), 2, java.math.RoundingMode.HALF_UP);
    }

    private static Row row(String key, String label, String hint, int weight, long count) {
        return new Row(key, label, hint, weight, count, count * weight);
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
