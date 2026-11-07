package com.salonreview.marketing;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.square.SquareClientProvider;
import com.salonreview.web.dto.MarketingConversionsDto;
import com.salonreview.web.dto.MarketingConversionsDto.Row;
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

    public MarketingConversionsDto conversions(String slugParam, LocalDate periodFrom, LocalDate periodTo) {
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

            long views = repository.countPageViews(pageId.get(), from, to);
            long sms = repository.countClickVisitors(pageId.get(), "sms", from, to);
            long calls = repository.countClickVisitors(pageId.get(), "phone", from, to);

            List<MarketingConversionsRepository.ContactRow> contacts = repository.findContacts(businessId, slug, from, to);
            long consultations = contacts.stream().filter(c -> c.bookingId() != null).count();

            Map<String, Instant> firstSeen = new HashMap<>();
            for (var c : contacts) {
                if (c.customerId() != null) firstSeen.merge(c.customerId(), c.createdAt(), (a, b) -> a.isBefore(b) ? a : b);
            }
            Map<String, BigDecimal> paidTotal = new HashMap<>();
            Set<String> deposit = new HashSet<>();
            for (var p : repository.findPayments(businessId, firstSeen.keySet())) {
                if (p.createdAt().isBefore(firstSeen.get(p.customerId()))) continue;
                paidTotal.merge(p.customerId(), p.amount(), BigDecimal::add);
                if (p.amount().compareTo(DEPOSIT_MIN) >= 0) deposit.add(p.customerId());
            }
            long full = paidTotal.entrySet().stream()
                    .filter(e -> deposit.contains(e.getKey()) && e.getValue().compareTo(FULL_MIN_TOTAL) >= 0).count();
            BigDecimal revenue = paidTotal.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

            List<Row> rows = new ArrayList<>();
            rows.add(row("sms", "Text message tap", "Visitors who tapped a text link", W_SMS, sms));
            rows.add(row("call", "Call tap", "Visitors who tapped a phone link", W_CALL, calls));
            rows.add(row("consultation", "Consultation booked", "People who booked a consultation here", W_CONSULTATION, consultations));
            rows.add(row("deposit", "Deposit paid", "Paid $100 or more in Square after leaving details", W_DEPOSIT, deposit.size()));
            rows.add(row("full", "Full payment", "Paid $300 or more in total (deposit plus procedure)", W_FULL, full));
            long total = rows.stream().mapToLong(Row::points).sum();
            return new MarketingConversionsDto(true, slug, views, rows, total, paidTotal.size(), revenue);
        } catch (DataAccessException e) {
            return MarketingConversionsDto.unavailable(slugParam);
        }
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
