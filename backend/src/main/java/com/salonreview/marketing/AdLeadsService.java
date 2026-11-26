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
                       java.math.BigDecimal totalPaid, List<StatusStep> steps, LeadFollowUpService.Outlook outlook,
                       Map<String, String> firstTouch) {}

    /** One campaign's leads (created in the period): money collected so far and still to collect for procedures booked ahead. */
    public record CampaignMoney(String campaign, int leads, java.math.BigDecimal collected, int withUpcoming,
                                java.math.BigDecimal remaining, Long campaignId, int siteDeposits, int payers) {}

    /**
     * {@code left*} covers every Google Ads lead with a procedure booked ahead, whenever the lead was created;
     * {@code collected} and {@code campaigns} cover the leads created in the selected period.
     */
    public record Money(java.math.BigDecimal leftToCollect, int leftLeads, java.math.BigDecimal leftEstimate,
                        java.math.BigDecimal leftPrepaid, boolean partial, java.math.BigDecimal collected,
                        java.math.BigDecimal periodLeft, int periodLeftLeads, List<CampaignMoney> campaigns,
                        java.math.BigDecimal periodEstimate, java.math.BigDecimal periodPrepaid,
                        int siteDeposits, Map<String, Integer> siteDepositsByPage, int payers) {}

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
        java.math.BigDecimal periodLeft = zero, periodEstimate = zero, periodPrepaid = zero;
        int leftLeads = 0, periodLeftLeads = 0, siteDeposits = 0, payers = 0;
        List<Long> siteDepositLeads = new java.util.ArrayList<>();
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
            int[] n = counts.computeIfAbsent(key, k -> new int[4]);
            n[0]++;
            if (l.squareCustomerId() != null && leads.siteDeposit(l)) {
                n[2]++;
                siteDeposits++;
                siteDepositLeads.add(l.id());
            }
            if (o != null && o.collected().signum() > 0) {
                n[3]++;
                payers++;
            }
            if (o != null) {
                sums[0] = sums[0].add(o.collected());
                collected = collected.add(o.collected());
                if (ahead) {
                    n[1]++;
                    sums[1] = sums[1].add(o.remaining());
                    periodLeft = periodLeft.add(o.remaining());
                    periodEstimate = periodEstimate.add(o.estimate());
                    periodPrepaid = periodPrepaid.add(o.prepaid());
                    if (o.remaining().signum() > 0) periodLeftLeads++;
                }
            }
        }
        // A lead carries its utm_campaign text, which is written differently from the campaign's name in Google Ads
        // ("Inweb_Search_Academy" vs "Inweb / Search / Academy / макс конв"), so rows are merged by the resolved campaign id.
        Map<String, Long> ids = campaignIds(byCampaign.keySet());
        Map<String, CampaignAgg> merged = new java.util.LinkedHashMap<>();
        for (var e : byCampaign.entrySet()) {
            Long id = ids.get(e.getKey());
            CampaignAgg agg = merged.computeIfAbsent(id != null ? "id:" + id : "raw:" + e.getKey(),
                    k -> new CampaignAgg(e.getKey().isEmpty() ? null : e.getKey(), id));
            agg.leads += counts.get(e.getKey())[0];
            agg.withUpcoming += counts.get(e.getKey())[1];
            agg.siteDeposits += counts.get(e.getKey())[2];
            agg.payers += counts.get(e.getKey())[3];
            agg.collected = agg.collected.add(e.getValue()[0]);
            agg.remaining = agg.remaining.add(e.getValue()[1]);
        }
        List<CampaignMoney> rows = merged.values().stream()
                .map(a -> new CampaignMoney(a.name, a.leads, a.collected, a.withUpcoming, a.remaining, a.id, a.siteDeposits, a.payers))
                .sorted((x, y) -> y.collected().add(y.remaining()).compareTo(x.collected().add(x.remaining()))).toList();
        return new Money(left, leftLeads, estimate, prepaid, partial, collected, periodLeft, periodLeftLeads, rows, periodEstimate, periodPrepaid, siteDeposits,
                siteDepositsByPage(siteDepositLeads), payers);
    }

    /** Landing page slugs of the tracked pages, as stored in marketing.landing_pages. */
    static final String PAGE_BOOK = "pmu-book", PAGE_ACADEMY = "academy", PAGE_SITE = "pmu-website";

    /** The tracked page a first-landing path belongs to; "" when the path was never recorded. */
    static String pageSlug(String landing) {
        if (landing == null || landing.isBlank()) return "";
        String path = landing.replaceFirst("^https?://[^/]+", "").toLowerCase(java.util.Locale.ROOT);
        if (path.startsWith("/book")) return PAGE_BOOK;
        if (path.startsWith("/academy")) return PAGE_ACADEMY;
        return PAGE_SITE;
    }

    /** Site deposits per landing page: the page the lead first landed on, "" when that was not recorded. */
    private Map<String, Integer> siteDepositsByPage(List<Long> leadIds) {
        Map<String, Integer> out = new HashMap<>();
        if (leadIds.isEmpty()) return out;
        String marks = String.join(",", java.util.Collections.nCopies(leadIds.size(), "?"));
        Object[] args = new Object[leadIds.size() + 1];
        args[0] = business.id();
        for (int i = 0; i < leadIds.size(); i++) args[i + 1] = leadIds.get(i);
        jdbc.query("SELECT COALESCE(NULLIF(attribution->>'ad_first_landing', ''), NULLIF(attribution->>'landing_url', '')) AS landing "
                + "FROM lead_intake WHERE business_id = ? AND id IN (" + marks + ")",
                rs -> { out.merge(pageSlug(rs.getString("landing")), 1, Integer::sum); }, args);
        return out;
    }

    /** Leads of one source created in the period: how many there are, how many paid, and what they paid since their first day. */
    public record Cohort(int leads, int payers, java.math.BigDecimal collected) {}

    public Cohort cohort(String source, LocalDate from, LocalDate to) {
        var items = leads.search(new LeadFollowUpService.LeadFilter(List.of(source), null, from, to, null, null,
                "created", false, false), 0, 1000).items();
        int n = 0, payers = 0;
        java.math.BigDecimal collected = java.math.BigDecimal.ZERO;
        for (var item : items) {
            n++;
            var l = item.lead();
            if (l.squareCustomerId() == null) continue;
            java.math.BigDecimal c = leads.collectedSinceFirstDay(l);
            if (c.signum() > 0) payers++;
            collected = collected.add(c);
        }
        return new Cohort(n, payers, collected);
    }

    private static final class CampaignAgg {
        final String name;
        final Long id;
        int leads, withUpcoming, siteDeposits, payers;
        java.math.BigDecimal collected = java.math.BigDecimal.ZERO, remaining = java.math.BigDecimal.ZERO;
        CampaignAgg(String name, Long id) { this.name = name; this.id = id; }
    }

    private Map<String, Long> campaignIds(java.util.Collection<String> raw) {
        List<Map.Entry<Long, String>> ads = jdbc.query("SELECT DISTINCT campaign_id, campaign_name FROM google_ads_daily WHERE business_id = ?",
                (rs, i) -> Map.entry(rs.getLong(1), rs.getString(2)), business.id());
        return resolveCampaignIds(raw, ads);
    }

    /** Words of a campaign label: lower case, accents dropped, split on anything that is not a letter or digit. */
    static List<String> words(String label) {
        String plain = java.text.Normalizer.normalize(label == null ? "" : label, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return java.util.Arrays.stream(plain.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{Nd}]+")).filter(w -> !w.isEmpty()).toList();
    }

    /**
     * Maps each lead campaign text to a Google Ads campaign id: the words of one label must start the other's (any name the
     * campaign had), with at least three shared words; the longest match wins and a tie between two campaigns stays unmatched.
     */
    static Map<String, Long> resolveCampaignIds(java.util.Collection<String> raw, List<Map.Entry<Long, String>> ads) {
        Map<String, Long> out = new HashMap<>();
        for (String r : raw) {
            List<String> u = words(r);
            int best = 0;
            java.util.Set<Long> bestIds = new java.util.HashSet<>();
            for (var ad : ads) {
                List<String> n = words(ad.getValue());
                int c = 0;
                while (c < u.size() && c < n.size() && u.get(c).equals(n.get(c))) c++;
                if (c < 3 || c != Math.min(u.size(), n.size())) continue;
                if (c > best) { best = c; bestIds.clear(); }
                if (c == best) bestIds.add(ad.getKey());
            }
            if (bestIds.size() == 1) out.put(r, bestIds.iterator().next());
        }
        return out;
    }

    /** utm_campaign and the search query for each lead: the lead's own capture, else its browser's first tagged visit. */
    private Map<Long, String[]> attributionOf(List<Long> ids) {
        Map<Long, String[]> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        String marks = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        Object[] args = new Object[ids.size() + 1];
        args[0] = business.id();
        for (int i = 0; i < ids.size(); i++) args[i + 1] = ids.get(i);
        // The lead row itself first, then the same person's other rows and website submissions (a consultation booking
        // used to be saved without attribution while the form or booking submission kept the campaign and keyword).
        String person = "(l.phone_number IS NOT NULL AND l.phone_number <> '' AND %s.phone_number = l.phone_number)";
        jdbc.query("SELECT l.id, "
                + "COALESCE(NULLIF(l.attribution->>'utm_campaign', ''), "
                + "(SELECT x.attribution->>'utm_campaign' FROM lead_intake x WHERE x.business_id = l.business_id AND " + person.formatted("x")
                + " AND COALESCE(x.attribution->>'utm_campaign', '') <> '' ORDER BY x.created_at LIMIT 1), "
                + "(SELECT s.utm_campaign FROM marketing.submissions s WHERE s.business_id = l.business_id AND COALESCE(s.utm_campaign, '') <> '' "
                + "AND (s.customer_phone = l.phone_number OR (COALESCE(l.email, '') <> '' AND lower(s.customer_email) = lower(l.email))) "
                + "ORDER BY s.occurred_at LIMIT 1)) AS campaign, "
                + "COALESCE(NULLIF(l.attribution->>'utm_term', ''), (regexp_match(l.attribution->>'landing_url', '[?&]utm_term=([^&]+)'))[1], "
                + "(SELECT v.utm_term FROM marketing.visits v WHERE v.business_id = l.business_id AND v.visitor_id = l.visitor_id "
                + "AND COALESCE(v.utm_term, '') <> '' ORDER BY v.occurred_at LIMIT 1), "
                + "(SELECT s.utm_term FROM marketing.submissions s WHERE s.business_id = l.business_id AND COALESCE(s.utm_term, '') <> '' "
                + "AND (s.customer_phone = l.phone_number OR (COALESCE(l.email, '') <> '' AND lower(s.customer_email) = lower(l.email))) "
                + "ORDER BY s.occurred_at LIMIT 1)) AS term "
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
                lead.lostReason(), lead.lostComment(), firstVisit, appointments, d.payments(), total, steps, d.outlook(),
                ft == null ? Map.of() : ft);
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
