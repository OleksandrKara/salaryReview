package com.salonreview.marketing;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.AdSpendEntry;
import com.salonreview.repo.AdSpendEntryRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Customer acquisition cost by paid channel: ad spend of the period divided by the new customers who paid after arriving from
 * that channel (leads created in the period that paid something since their first day, the figure Collected on the Google PPC
 * tab uses). Google Ads spend comes from the daily sync; Meta spend is the amounts entered by hand per landing page
 * ({@code ad_spend_entries}, prorated to the period, flagged when estimated).
 */
@Service
public class MarketingCacService {

    public record Channel(String key, String label, BigDecimal spend, boolean spendEstimated, int leads, int payers,
                          BigDecimal collected, BigDecimal cac, BigDecimal cpl, BigDecimal roas) {}

    public record Cac(List<Channel> channels, Channel total) {}

    private final AdLeadsService ads;
    private final JdbcTemplate jdbc;
    private final AdSpendEntryRepository spendEntries;
    private final CurrentBusinessContext business;

    public MarketingCacService(AdLeadsService ads, JdbcTemplate jdbc, AdSpendEntryRepository spendEntries, CurrentBusinessContext business) {
        this.ads = ads;
        this.jdbc = jdbc;
        this.spendEntries = spendEntries;
        this.business = business;
    }

    public Cac cac(LocalDate from, LocalDate to) {
        List<Channel> out = new ArrayList<>();
        BigDecimal googleSpend = googleSpend(from, to);
        var google = ads.cohort("Google Ads", from, to);
        if (googleSpend.signum() > 0 || google.leads() > 0) {
            out.add(channel("google_ads", "Google Ads", googleSpend, false, google));
        }
        Resolved meta = metaSpend(from, to);
        var metaCohort = ads.cohort("Meta Ads", from, to);
        if (meta.amount().signum() > 0 || metaCohort.leads() > 0) {
            out.add(channel("meta_ads", "Meta Ads", meta.amount(), meta.estimated(), metaCohort));
        }
        BigDecimal spend = BigDecimal.ZERO, collected = BigDecimal.ZERO;
        int leads = 0, payers = 0;
        boolean estimated = false;
        for (Channel c : out) {
            spend = spend.add(c.spend());
            collected = collected.add(c.collected());
            leads += c.leads();
            payers += c.payers();
            estimated |= c.spendEstimated();
        }
        Channel total = channel("total", "All paid channels", spend, estimated, new AdLeadsService.Cohort(leads, payers, collected));
        return new Cac(out, total);
    }

    private record Resolved(BigDecimal amount, boolean estimated) {}

    static Channel channel(String key, String label, BigDecimal spend, boolean estimated, AdLeadsService.Cohort c) {
        return new Channel(key, label, spend, estimated, c.leads(), c.payers(), c.collected(), per(spend, c.payers()), per(spend, c.leads()),
                spend.signum() > 0 ? c.collected().divide(spend, 2, RoundingMode.HALF_UP) : null);
    }

    private static BigDecimal per(BigDecimal spend, int n) {
        return n > 0 && spend.signum() > 0 ? spend.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP) : null;
    }

    private BigDecimal googleSpend(LocalDate from, LocalDate to) {
        BigDecimal micros = jdbc.queryForObject("SELECT COALESCE(SUM(cost_micros), 0) FROM google_ads_daily WHERE business_id = ? "
                        + "AND (?::date IS NULL OR day >= ?) AND (?::date IS NULL OR day <= ?)", BigDecimal.class, business.id(),
                from == null ? null : java.sql.Date.valueOf(from), from == null ? null : java.sql.Date.valueOf(from),
                to == null ? null : java.sql.Date.valueOf(to), to == null ? null : java.sql.Date.valueOf(to));
        return micros.divide(BigDecimal.valueOf(1_000_000), 2, RoundingMode.HALF_UP);
    }

    private Resolved metaSpend(LocalDate from, LocalDate to) {
        LocalDate f = from == null ? LocalDate.of(2000, 1, 1) : from;
        LocalDate t = to == null ? LocalDate.now() : to;
        List<String> slugs = jdbc.queryForList("SELECT DISTINCT landing_page_slug FROM ad_spend_entries WHERE business_id = ?", String.class, business.id());
        BigDecimal total = BigDecimal.ZERO;
        boolean estimated = false;
        for (String slug : slugs) {
            List<AdSpendEntry> entries = spendEntries.findOverlapping(slug, f, t, business.id());
            var r = AdSpendResolver.resolve(entries, f, t);
            total = total.add(r.amount());
            estimated |= r.estimated() && !entries.isEmpty();
        }
        return new Resolved(total, estimated);
    }
}
