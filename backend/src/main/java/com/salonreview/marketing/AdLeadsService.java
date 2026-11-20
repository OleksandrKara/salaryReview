package com.salonreview.marketing;

import com.salonreview.config.CurrentBusinessContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only lead views for the Ads Manager role: the Google Ads lead list and a trimmed lead card.
 * The card carries what an advertiser needs to judge a lead (where it came from, the search query,
 * the consultation and the procedure booked after it, what was paid) and none of the conversation
 * (texts, emails, call recordings, staff notes) or any write action.
 */
@Service
public class AdLeadsService {

    static final String GOOGLE_SOURCE = "Google Ads";
    static final int LIST_LIMIT = 300;

    public record LeadRow(long id, String name, String status, String createdAt, String campaign, String searchQuery,
                          String consultation, String procedure, String lostReason, String customerId,
                          java.math.BigDecimal collected, java.math.BigDecimal remaining, String nextProcedureAt) {}

    public record Appointment(String status, String startAt, String services, String technician,
                              java.math.BigDecimal paid, boolean consultation) {}

    public record Visit(String at, String landing, String trafficSource, String utm, String adClick, String device) {}

    public record StatusStep(String at, String text) {}

    public record Card(long id, String name, String phone, String email, String status, String createdAt, String source,
                       String campaign, String searchQuery, String landing, String lostReason, String lostComment,
                       Visit firstVisit, List<Appointment> appointments, List<LeadFollowUpService.Payment> payments,
                       java.math.BigDecimal totalPaid, List<StatusStep> steps, LeadFollowUpService.Outlook outlook) {}

    /** One campaign's leads (created in the period): money collected so far and still to collect for procedures booked ahead. */
    public record CampaignMoney(String campaign, int leads, java.math.BigDecimal collected, int withUpcoming,
                                java.math.BigDecimal remaining) {}

    /**
     * {@code left*} covers every Google Ads lead with a procedure booked ahead, whenever the lead was created;
     * {@code collected} and {@code campaigns} cover the leads created in the selected period.
     */
    public record Money(java.math.BigDecimal leftToCollect, int leftLeads, java.math.BigDecimal leftEstimate,
                        java.math.BigDecimal leftPrepaid, boolean partial, java.math.BigDecimal collected,
                        java.math.BigDecimal periodLeft, int periodLeftLeads, List<CampaignMoney> campaigns) {}

    private final LeadFollowUpService leads;
    private final JdbcTemplate jdbc;
    private final CurrentBusinessContext business;

    public AdLeadsService(LeadFollowUpService leads, JdbcTemplate jdbc, CurrentBusinessContext business) {
        this.leads = leads;
        this.jdbc = jdbc;
        this.business = business;
    }

    /** Leads whose traffic source is Google Ads, created in the period (Los Angeles days), newest first. */
    public List<LeadRow> googleLeads(LocalDate from, LocalDate to) {
        var filter = new LeadFollowUpService.LeadFilter(List.of(GOOGLE_SOURCE), null, from, to, null, null,
                "created", false, false);
        var items = leads.search(filter, 0, LIST_LIMIT).items();
        Map<Long, String[]> attribution = attributionOf(items.stream().map(i -> i.lead().id()).toList());
        return items.stream().map(i -> {
            var l = i.lead();
            String[] a = attribution.getOrDefault(l.id(), new String[2]);
            String procedure = null;
            LeadFollowUpService.Outlook outlook = l.squareCustomerId() == null ? null : leads.outlookOf(l);
            if (l.squareCustomerId() != null) {
                var appts = leads.appointments(l);
                procedure = appts.stream().filter(x -> !"CANCELLED_BY_CUSTOMER".equals(x.status())
                                && !"CANCELLED_BY_SELLER".equals(x.status()) && x.consultKind() == null && x.services() != null)
                        .map(LeadFollowUpService.Appointment::services).findFirst().orElse(null);
            }
            boolean ahead = outlook != null && !outlook.upcoming().isEmpty();
            return new LeadRow(l.id(), l.name(), l.status(), l.createdAt(), a[0], a[1], l.consultState(), procedure,
                    l.lostReason(), l.squareCustomerId(), outlook == null ? null : outlook.collected(),
                    ahead ? outlook.remaining() : null, ahead ? outlook.upcoming().get(0).startAt() : null);
        }).toList();
    }

    public Money money(LocalDate from, LocalDate to) {
        java.math.BigDecimal zero = java.math.BigDecimal.ZERO;
        var everyone = leads.search(new LeadFollowUpService.LeadFilter(List.of(GOOGLE_SOURCE), null, null, null, null, null,
                "created", false, false), 0, 1000).items();
        java.util.Set<Long> cohort = leads.search(new LeadFollowUpService.LeadFilter(List.of(GOOGLE_SOURCE), null, from, to,
                null, null, "created", false, false), 0, 1000).items().stream().map(i -> i.lead().id())
                .collect(java.util.stream.Collectors.toSet());
        Map<Long, String[]> attribution = attributionOf(everyone.stream().map(i -> i.lead().id()).toList());
        java.math.BigDecimal left = zero, estimate = zero, prepaid = zero, collected = zero;
        java.math.BigDecimal periodLeft = zero;
        int leftLeads = 0, periodLeftLeads = 0;
        boolean partial = false;
        Map<String, java.math.BigDecimal[]> byCampaign = new java.util.LinkedHashMap<>();
        Map<String, int[]> counts = new HashMap<>();
        for (var item : everyone) {
            var l = item.lead();
            var o = l.squareCustomerId() == null ? null : leads.outlookOf(l);
            boolean ahead = o != null && !o.upcoming().isEmpty();
            if (ahead) {
                left = left.add(o.remaining());
                estimate = estimate.add(o.estimate());
                prepaid = prepaid.add(o.prepaid());
                partial |= o.partial();
                if (o.remaining().signum() > 0) leftLeads++;
            }
            if (!cohort.contains(l.id())) continue;
            String a = attribution.getOrDefault(l.id(), new String[2])[0];
            String key = a == null ? "" : a;
            var sums = byCampaign.computeIfAbsent(key, k -> new java.math.BigDecimal[]{zero, zero});
            int[] n = counts.computeIfAbsent(key, k -> new int[2]);
            n[0]++;
            if (o != null) {
                sums[0] = sums[0].add(o.collected());
                collected = collected.add(o.collected());
                if (ahead) {
                    n[1]++;
                    sums[1] = sums[1].add(o.remaining());
                    periodLeft = periodLeft.add(o.remaining());
                    if (o.remaining().signum() > 0) periodLeftLeads++;
                }
            }
        }
        List<CampaignMoney> rows = byCampaign.entrySet().stream()
                .map(e -> new CampaignMoney(e.getKey().isEmpty() ? null : e.getKey(), counts.get(e.getKey())[0],
                        e.getValue()[0], counts.get(e.getKey())[1], e.getValue()[1]))
                .sorted((x, y) -> y.collected().add(y.remaining()).compareTo(x.collected().add(x.remaining()))).toList();
        return new Money(left, leftLeads, estimate, prepaid, partial, collected, periodLeft, periodLeftLeads, rows);
    }

    /** utm_campaign and the search query for each lead: the lead's own capture, else its browser's first tagged visit. */
    private Map<Long, String[]> attributionOf(List<Long> ids) {
        Map<Long, String[]> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        String marks = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        Object[] args = new Object[ids.size() + 1];
        args[0] = business.id();
        for (int i = 0; i < ids.size(); i++) args[i + 1] = ids.get(i);
        jdbc.query("SELECT l.id, l.attribution->>'utm_campaign' AS campaign, "
                + "COALESCE(NULLIF(l.attribution->>'utm_term', ''), (regexp_match(l.attribution->>'landing_url', '[?&]utm_term=([^&]+)'))[1], "
                + "(SELECT v.utm_term FROM marketing.visits v WHERE v.business_id = l.business_id AND v.visitor_id = l.visitor_id "
                + "AND COALESCE(v.utm_term, '') <> '' ORDER BY v.occurred_at LIMIT 1)) AS term "
                + "FROM lead_intake l WHERE l.business_id = ? AND l.id IN (" + marks + ")", rs -> {
            out.put(rs.getLong("id"), new String[]{blank(rs.getString("campaign")), term(rs.getString("term"))});
        }, args);
        return out;
    }

    public Card card(long requestedId) {
        var d = leads.detail(requestedId);
        var lead = d.lead();
        String campaign = null;
        String query = null;
        String landing = null;
        var ft = d.firstTouch();
        if (ft != null) {
            campaign = blank(ft.get("utm_campaign"));
            query = term(ft.get("utm_term"));
            landing = blank(ft.get("ad_first_landing"));
            if (query == null) query = termFromUrl(ft.get("landing_url"));
        }
        for (var o : d.origins()) {
            if (campaign == null) campaign = blank(o.utmCampaign());
            if (query == null) query = term(o.utmTerm());
            if (landing == null) landing = blank(o.landingPage());
        }
        var first = d.journey() == null ? null : d.journey().first();
        var firstVisit = first == null ? null : new Visit(first.at(), first.landing(), first.trafficSource(), first.utm(), first.adClick(), first.device());
        var appointments = d.appointments().stream()
                .map(a -> new Appointment(a.status(), a.startAt(), a.services(), a.technician(), a.paid(), a.consultKind() != null))
                .toList();
        java.math.BigDecimal total = d.spend() == null ? java.math.BigDecimal.ZERO : d.spend().total();
        // Status changes only: staff notes, texts and emails stay out of this view.
        var steps = d.events().stream().filter(e -> "STATUS".equals(e.type()))
                .map(e -> new StatusStep(e.createdAt(), e.text())).toList();
        return new Card(lead.id(), d.nameInfo() != null && d.nameInfo().name() != null ? d.nameInfo().name() : lead.name(),
                lead.phoneNumber(), lead.email(), lead.status(), lead.createdAt(), lead.source(), campaign, query, landing,
                lead.lostReason(), lead.lostComment(), firstVisit, appointments, d.payments(), total, steps, d.outlook());
    }

    private static String termFromUrl(String url) {
        if (url == null) return null;
        var m = java.util.regex.Pattern.compile("[?&]utm_term=([^&]+)").matcher(url);
        return m.find() ? term(m.group(1)) : null;
    }

    /** Search queries arrive URL-encoded; empty or unfilled ValueTrack placeholders mean "not provided". */
    static String term(String raw) {
        if (raw == null) return null;
        String t;
        try {
            t = URLDecoder.decode(raw, StandardCharsets.UTF_8).trim();
        } catch (IllegalArgumentException e) {
            t = raw.trim();
        }
        return t.isEmpty() || t.startsWith("{") ? null : t;
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
