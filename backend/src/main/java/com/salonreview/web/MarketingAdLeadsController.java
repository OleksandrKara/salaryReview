package com.salonreview.web;

import com.salonreview.marketing.AdLeadsService;
import com.salonreview.marketing.LeadFollowUpService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * What the Ads Manager role sees about leads. Every endpoint is a GET under {@code /api/owner/marketing/**},
 * so SecurityConfig's OWNER + ADS_MANAGER read gate applies and nothing here can change a lead.
 */
@RestController
@RequestMapping("/api/owner/marketing")
public class MarketingAdLeadsController {

    private final AdLeadsService ads;
    private final LeadFollowUpService leads;

    public MarketingAdLeadsController(AdLeadsService ads, LeadFollowUpService leads) {
        this.ads = ads;
        this.leads = leads;
    }

    /** Google Ads leads created in the period, for the Google PPC tab. */
    @GetMapping("/google-ppc/leads")
    public List<AdLeadsService.LeadRow> googleLeads(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ads.googleLeads(from, to);
    }

    /** Left to collect for Google Ads leads with a procedure booked ahead, and collected / left per campaign. */
    @GetMapping("/google-ppc/money")
    public AdLeadsService.Money money(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ads.money(from, to);
    }

    @GetMapping("/leads/{id}")
    public AdLeadsService.Card lead(@PathVariable long id) {
        return ads.card(id);
    }

    /** Same report the CRM shows (contact details limited to the last four phone digits). */
    @GetMapping("/lost-report")
    public LeadFollowUpService.LostReport lostReport(
            @RequestParam(defaultValue = "90") int days,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return leads.lostReport(days, from, to);
    }
}
