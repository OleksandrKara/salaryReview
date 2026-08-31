package com.salonreview.web;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.sms.EmailDomainHealthService;
import com.salonreview.sms.MailchimpConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * OWNER-only: SPF/DKIM/DMARC/MX health for this business's own sending domain (the domain half of
 * {@code mailchimp_config.from_email}) — see {@link EmailDomainHealthService}. Grew directly out
 * of the pmu-annakara.com toll-free-verification investigation (2026-08-31): a dangling MX record
 * and missing SPF/DKIM were only found by manually running {@code dig} over and over across a long
 * back-and-forth — this surfaces the same checks on {@code /owner/settings/automations?tab=email}
 * instead. Falls under the existing {@code /api/owner/**} matcher in
 * {@link com.salonreview.config.SecurityConfig} — no new security config needed.
 */
@RestController
@RequestMapping("/api/owner/settings/email-domain-health")
public class EmailDomainHealthController {

    private final EmailDomainHealthService healthService;
    private final MailchimpConfigService mailchimpConfig;
    private final CurrentBusinessContext currentBusinessContext;

    public EmailDomainHealthController(EmailDomainHealthService healthService, MailchimpConfigService mailchimpConfig,
                                        CurrentBusinessContext currentBusinessContext) {
        this.healthService = healthService;
        this.mailchimpConfig = mailchimpConfig;
        this.currentBusinessContext = currentBusinessContext;
    }

    @GetMapping
    public ResponseEntity<EmailDomainHealthDto> get() {
        String fromEmail = mailchimpConfig.get(currentBusinessContext.id()).getFromEmail();
        String domain = domainOf(fromEmail);
        if (domain == null) {
            return ResponseEntity.ok(new EmailDomainHealthDto(
                    false, null, null, null, null, null, null, null, Instant.now()));
        }
        EmailDomainHealthService.Result r = healthService.check(domain);
        return ResponseEntity.ok(new EmailDomainHealthDto(
                true, r.domain(), r.score(), r.rating(),
                toCheckDto(r.spf()), toCheckDto(r.dkim()), toCheckDto(r.dmarc()), toCheckDto(r.mx()),
                r.checkedAt()));
    }

    private static CheckDto toCheckDto(EmailDomainHealthService.Check c) {
        return new CheckDto(c.pass(), c.detail());
    }

    private static String domainOf(String email) {
        if (email == null || email.isBlank()) return null;
        int at = email.indexOf('@');
        return at < 0 || at == email.length() - 1 ? null : email.substring(at + 1).trim();
    }

    /** {@code configured == false} — no {@code from_email} set for this business yet, every other
     * field is {@code null} and nothing was checked (no point running DNS lookups against a domain
     * we don't even have). */
    public record EmailDomainHealthDto(boolean configured, String domain, Integer score, String rating,
                                        CheckDto spf, CheckDto dkim, CheckDto dmarc, CheckDto mx,
                                        Instant checkedAt) {
    }

    public record CheckDto(boolean pass, String detail) {
    }
}
