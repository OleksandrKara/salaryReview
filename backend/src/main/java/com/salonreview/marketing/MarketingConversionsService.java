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

    private record Draft(MarketingConversionsRepository.ContactRow first, MarketingConversionsRepository.ContactRow booking,
                         List<GooglePpcDto.Payment> payments, BigDecimal total) {}

    /** campaignCounts: Google Ads conversions of this page per campaign key, {sms taps, call taps, consultations}. */
    private record PageResult(Map<String, Agg> agg, Map<String, BigDecimal> googlePaid, Set<String> googleDeposit,
                              Map<String, Draft> googleDrafts, Map<String, long[]> campaignCounts) {}

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
        Map<String, Draft> drafts = new HashMap<>();
        Map<String, long[]> perCampaign = new HashMap<>();
        Map<String, String> pageNames = new HashMap<>();
        for (var lp : dashboardRepository.listLandingPages(businessId)) {
            pageNames.put(lp.slug(), lp.name());
            Optional<UUID> id = dashboardRepository.findLandingPageId(lp.slug(), businessId);
            if (id.isEmpty()) continue;
            PageResult r = compute(businessId, lp.slug(), id.get(), periodFrom, periodTo, zone);
            Agg g = r.agg().get(GOOGLE);
            total.views += g.views; total.sms += g.sms; total.calls += g.calls; total.consultations += g.consultations;
            r.googlePaid().forEach((c, amt) -> paid.merge(c, amt, BigDecimal::max));
            deposit.addAll(r.googleDeposit());
            r.googleDrafts().forEach((c, d) -> drafts.merge(c, d, (a, b) -> b.total().compareTo(a.total()) > 0 ? b : a));
            r.campaignCounts().forEach((k, v) -> {
                long[] c = perCampaign.computeIfAbsent(k, x -> new long[6]);
                for (int i = 0; i < 3; i++) c[i] += v[i];
            });
            if (g.views > 0 || g.consultations > 0 || g.revenue.signum() > 0) {
                pages.add(new GooglePpcDto.PageResults(lp.slug(), lp.name(), g.views, g.sms, g.calls,
                        g.consultations, g.deposits, g.full, g.revenue, 0, 0));
            }
        }
        BigDecimal revenue = BigDecimal.ZERO;
        long full = 0;
        for (var e : paid.entrySet()) {
            revenue = revenue.add(e.getValue());
            if (deposit.contains(e.getKey()) && e.getValue().compareTo(FULL_MIN_TOTAL) >= 0) full++;
        }
        Instant smsFrom = periodFrom == null ? null : periodFrom.atStartOfDay(zone).toInstant();
        Instant smsTo = periodTo == null ? null : periodTo.plusDays(1).atStartOfDay(zone).toInstant();
        Map<String, Long> smsByPage = repository.googleSmsReceivedByPage(businessId, smsFrom, smsTo);
        for (int i = 0; i < pages.size(); i++) {
            var p = pages.get(i);
            pages.set(i, new GooglePpcDto.PageResults(p.slug(), p.name(), p.visitors(), p.smsTaps(), p.callTaps(), p.consultations(),
                    p.deposits(), p.fullPayments(), p.revenue(), p.forms(), smsByPage.getOrDefault(p.slug(), 0L)));
        }
        // The Academy request form is a conversion of its own: Google-ad requests in the period, on the Academy page.
        Instant formsFrom = periodFrom == null ? null : periodFrom.atStartOfDay(zone).toInstant();
        Instant formsTo = periodTo == null ? null : periodTo.plusDays(1).atStartOfDay(zone).toInstant();
        List<String> formTexts = repository.googleAcademyForms(businessId, formsFrom, formsTo);
        for (String text : formTexts) perCampaign.computeIfAbsent(text == null || text.isBlank() ? "" : "name:" + text, x -> new long[6])[3]++;
        List<String> callTexts = repository.googleRingostatCalls(businessId, formsFrom, formsTo);
        for (String text : callTexts) perCampaign.computeIfAbsent(text == null || text.isBlank() ? "" : "name:" + text, x -> new long[6])[4]++;
        List<String> smsTexts = repository.googleSmsReceived(businessId, formsFrom, formsTo);
        for (String text : smsTexts) perCampaign.computeIfAbsent(text == null || text.isBlank() ? "" : "name:" + text, x -> new long[6])[5]++;
        if (!formTexts.isEmpty()) {
            int at = -1;
            for (int i = 0; i < pages.size(); i++) if (AdLeadsService.PAGE_ACADEMY.equals(pages.get(i).slug())) at = i;
            if (at >= 0) {
                var q = pages.get(at);
                pages.set(at, new GooglePpcDto.PageResults(q.slug(), q.name(), q.visitors(), q.smsTaps(), q.callTaps(), q.consultations(),
                        q.deposits(), q.fullPayments(), q.revenue(), formTexts.size(), q.smsReceived()));
            } else {
                pages.add(new GooglePpcDto.PageResults(AdLeadsService.PAGE_ACADEMY, pageNames.getOrDefault(AdLeadsService.PAGE_ACADEMY, "Academy"),
                        0, 0, 0, 0, 0, 0, BigDecimal.ZERO, formTexts.size(), smsByPage.getOrDefault(AdLeadsService.PAGE_ACADEMY, 0L)));
            }
        }
        var results = new GooglePpcDto.Results(total.views, total.sms, total.calls, total.consultations,
                deposit.size(), full, paid.size(), revenue, formTexts.size(), callTexts.size(), smsTexts.size());

        LocalDate from = periodFrom;
        var campaigns = repository.findGoogleAdsCampaigns(businessId, from, periodTo);
        // What Google Ads itself counts per GA4 event ("AnnaKara GA4 (web) sms_click" is shown as sms_click) so every number on the
        // page can be checked against the Ads or Analytics screens.
        Map<Long, Map<String, Double>> googleByCampaign = new HashMap<>();
        Map<String, Double> googleTotals = new java.util.TreeMap<>();
        for (var row : repository.googleConversionsByAction(businessId, periodFrom, periodTo)) {
            String event = row.actionName().replaceFirst("^.*\\(web\\)\\s*", "");
            googleByCampaign.computeIfAbsent(row.campaignId(), x -> new java.util.TreeMap<>()).merge(event, row.conversions(), Double::sum);
            googleTotals.merge(event, row.conversions(), Double::sum);
        }
        long cost = 0, imps = 0, clicks = 0;
        double conv = 0;
        List<GooglePpcDto.Campaign> out = new ArrayList<>();
        // Conversion keys are a Google Ads campaign id (clicks) or a utm_campaign text (consultations, forms); the text is
        // matched to a campaign the same way the lead money is. Whatever stays unmatched is reported apart so totals hold.
        Set<Long> inPeriod = new HashSet<>();
        for (var c : campaigns) inPeriod.add(c.campaignId());
        Map<String, Long> idOfText = AdLeadsService.resolveCampaignIds(perCampaign.keySet().stream()
                .filter(k -> k.startsWith("name:")).map(k -> k.substring(5)).toList(), repository.googleAdsCampaignNames(businessId));
        Map<Long, long[]> byId = new HashMap<>();
        long[] unattributed = new long[6];
        perCampaign.forEach((key, v) -> {
            Long id = key.startsWith("id:") ? Long.valueOf(key.substring(3)) : key.startsWith("name:") ? idOfText.get(key.substring(5)) : null;
            long[] into = id != null && inPeriod.contains(id) ? byId.computeIfAbsent(id, x -> new long[6]) : unattributed;
            for (int i = 0; i < 6; i++) into[i] += v[i];
        });
        for (var c : campaigns) {
            cost += c.costMicros(); imps += c.impressions(); clicks += c.clicks(); conv += c.conversions();
            long[] k = byId.getOrDefault(c.campaignId(), new long[6]);
            out.add(new GooglePpcDto.Campaign(c.campaignId(), c.name(), c.status(), micros(c.costMicros()),
                    c.impressions(), c.clicks(), c.conversions(), k[0], k[1], k[2], k[3], k[4], k[5], googleByCampaign.getOrDefault(c.campaignId(), Map.of())));
        }
        var names = new HashMap<String, MarketingConversionsRepository.CustomerRow>();
        repository.findCustomers(businessId, drafts.keySet()).forEach(c -> names.put(c.customerId(), c));
        List<GooglePpcDto.PayingCustomer> customers = new ArrayList<>();
        drafts.forEach((cid, d) -> {
            var f = d.first();
            var b = d.booking();
            var sq = names.get(cid);
            String name = sq != null && sq.name() != null && !sq.name().isBlank() ? sq.name() : f.name();
            customers.add(new GooglePpcDto.PayingCustomer(cid, name,
                    sq != null && sq.phone() != null ? sq.phone() : f.phone(),
                    sq != null && sq.email() != null ? sq.email() : f.email(),
                    f.createdAt(), f.pageSlug(), f.campaign(),
                    b == null ? null : b.serviceName(), b == null ? null : b.bookingStatus(),
                    b == null ? null : b.bookingStartAt(), b == null ? null : b.artist(),
                    d.total(), deposit.contains(cid),
                    deposit.contains(cid) && d.total().compareTo(FULL_MIN_TOTAL) >= 0, d.payments()));
        });
        customers.sort((x, y) -> y.total().compareTo(x.total()));
        return new GooglePpcDto(!campaigns.isEmpty(), micros(cost), imps, clicks, conv, results, out, pages, customers,
                new GooglePpcDto.Counts(unattributed[0], unattributed[1], unattributed[2], unattributed[3], unattributed[4], unattributed[5]), googleTotals);
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

        Map<String, long[]> campaignCounts = new HashMap<>();
        repository.googleTapsByCampaign(pageId, from, to).forEach((k, v) -> {
            long[] c = campaignCounts.computeIfAbsent(k, x -> new long[3]);
            c[0] += v[0];
            c[1] += v[1];
        });

        Map<String, Instant> firstSeen = new HashMap<>();
        Map<String, String> customerChannel = new HashMap<>();
        Map<String, MarketingConversionsRepository.ContactRow> firstRow = new HashMap<>();
        Map<String, MarketingConversionsRepository.ContactRow> bookingRow = new HashMap<>();
        for (var c : repository.findContacts(businessId, slug, from, to)) {
            Agg a = agg.getOrDefault(c.channel(), agg.get("other"));
            if (c.bookingId() != null) {
                a.consultations++;
                if (GOOGLE.equals(c.channel())) {
                    String camp = c.campaign() == null || c.campaign().isBlank() ? "" : "name:" + c.campaign();
                    campaignCounts.computeIfAbsent(camp, x -> new long[3])[2]++;
                }
            }
            if (c.customerId() != null) {
                if (c.bookingId() != null) {
                    var cur = bookingRow.get(c.customerId());
                    if (cur == null || c.createdAt().isAfter(cur.createdAt())) bookingRow.put(c.customerId(), c);
                }
                Instant seen = firstSeen.get(c.customerId());
                if (seen == null || c.createdAt().isBefore(seen)) {
                    firstSeen.put(c.customerId(), c.createdAt());
                    firstRow.put(c.customerId(), c);
                    customerChannel.put(c.customerId(), agg.containsKey(c.channel()) ? c.channel() : "other");
                }
            }
        }
        Map<String, BigDecimal> paidTotal = new HashMap<>();
        Set<String> deposit = new HashSet<>();
        Map<String, List<GooglePpcDto.Payment>> payLists = new HashMap<>();
        for (var p : repository.findPayments(businessId, firstSeen.keySet())) {
            if (p.createdAt().isBefore(firstSeen.get(p.customerId()))) continue;
            payLists.computeIfAbsent(p.customerId(), k -> new ArrayList<>()).add(new GooglePpcDto.Payment(p.createdAt(), p.amount()));
            paidTotal.merge(p.customerId(), p.amount(), BigDecimal::add);
            if (p.amount().compareTo(DEPOSIT_MIN) >= 0) deposit.add(p.customerId());
        }
        Map<String, BigDecimal> googlePaid = new HashMap<>();
        Set<String> googleDeposit = new HashSet<>();
        Map<String, Draft> googleDrafts = new HashMap<>();
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
                var pl = payLists.get(e.getKey());
                pl.sort((x, y) -> x.at().compareTo(y.at()));
                googleDrafts.put(e.getKey(), new Draft(firstRow.get(e.getKey()), bookingRow.get(e.getKey()), pl, e.getValue()));
                if (dep) googleDeposit.add(e.getKey());
            }
        }
        return new PageResult(agg, googlePaid, googleDeposit, googleDrafts, campaignCounts);
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
