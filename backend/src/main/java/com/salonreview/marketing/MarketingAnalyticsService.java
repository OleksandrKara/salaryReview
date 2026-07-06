package com.salonreview.marketing;

import com.salonreview.domain.SalonConfig;
import com.salonreview.repo.SalonConfigRepository;
import com.salonreview.square.SquareMonthAggregator;
import com.salonreview.square.SquareMonthAggregator.AttributedService;
import com.salonreview.web.dto.MarketingAnalyticsDto;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class MarketingAnalyticsService {

    private final MarketingContactsRepository contactsRepository;
    private final SquareMonthAggregator aggregator;
    private final SalonConfigRepository salonConfig;

    public MarketingAnalyticsService(
            MarketingContactsRepository contactsRepository,
            SquareMonthAggregator aggregator,
            SalonConfigRepository salonConfig
    ) {
        this.contactsRepository = contactsRepository;
        this.aggregator = aggregator;
        this.salonConfig = salonConfig;
    }

    /** Gross revenue, customer count, and service count for ads-attributed customers with a
     * service rendered in [from, to] inclusive. Aggregates one calendar month at a time (the only
     * granularity SquareMonthAggregator offers) and concatenates, since a custom range can span
     * more than one month.
     */
    public MarketingAnalyticsDto analytics(LocalDate from, LocalDate to) {
        Set<String> adsCustomerIds = contactsRepository.findAdsAttributedSquareCustomerIds();
        if (adsCustomerIds.isEmpty()) {
            return new MarketingAnalyticsDto(from, to, 0, 0, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        }

        BigDecimal cutoff = priceCutoff();
        List<AttributedService> inRange = new ArrayList<>();
        for (YearMonth ym = YearMonth.from(from); !ym.isAfter(YearMonth.from(to)); ym = ym.plusMonths(1)) {
            SquareMonthAggregator.MonthAggregation agg = aggregator.aggregate(ym.getYear(), ym.getMonthValue(), cutoff);
            for (AttributedService s : agg.services()) {
                if (!adsCustomerIds.contains(s.customerId())) continue;
                LocalDate day = parseIso(s.date());
                if (day == null || day.isBefore(from) || day.isAfter(to)) continue;
                inRange.add(s);
            }
        }

        long customerCount = inRange.stream().map(AttributedService::customerId).distinct().count();
        BigDecimal gross = inRange.stream()
                .map(AttributedService::gross)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        return new MarketingAnalyticsDto(from, to, customerCount, inRange.size(), gross);
    }

    private BigDecimal priceCutoff() {
        SalonConfig cfg = salonConfig.findById(1)
                .orElseThrow(() -> new IllegalStateException("Salon config with id=1 is missing"));
        return cfg.getServicePriceCutoff();
    }

    private static LocalDate parseIso(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return LocalDate.parse(iso);
        } catch (Exception e) {
            return null;
        }
    }
}
