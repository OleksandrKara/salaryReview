package com.salonreview.sms;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SPF/DKIM/DMARC/MX health check for a business's sending domain — checks what's *configured* in
 * DNS (via {@link DnsResolver}), not a full deliverability probe like mail-tester.com (no mail is
 * actually sent, so no blacklist/content/reputation/inbox-placement check). That's deliberate:
 * it's the part an owner can actually act on from their DNS panel, checked instantly and safely on
 * every page load instead of a manual send-and-check round trip.
 *
 * <p>Grew directly out of the pmu-annakara.com toll-free-verification investigation (2026-08-31):
 * a dangling MX record and missing SPF/DKIM were only found by manually running {@code dig} back
 * and forth across a long conversation — this surfaces the same checks in the owner UI instead
 * (see {@code EmailDomainHealthController}, {@code /owner/settings/automations?tab=email}).
 *
 * <p>DKIM is only checked against the two providers this app actually integrates with —
 * Mailchimp's own domain-authentication CNAMEs and Google Workspace's TXT selector (the
 * overwhelmingly common choice for a business's own mailbox). An unrecognized/custom selector
 * (e.g. Microsoft 365's "selector1"/"selector2") isn't checked.
 */
@Service
public class EmailDomainHealthService {

    private record DkimSelector(String host, String provider, boolean isCname) {}

    private static final List<DkimSelector> DKIM_SELECTORS = List.of(
            new DkimSelector("k1._domainkey", "Mailchimp", true),
            new DkimSelector("k2._domainkey", "Mailchimp", true),
            new DkimSelector("google._domainkey", "Google Workspace", false));

    private static final Pattern DMARC_POLICY = Pattern.compile("p=([a-zA-Z]+)");

    private final DnsResolver dns;

    public EmailDomainHealthService(DnsResolver dns) {
        this.dns = dns;
    }

    public record Check(boolean pass, String detail) {}

    public record Result(String domain, int score, String rating,
                          Check spf, Check dkim, Check dmarc, Check mx, Instant checkedAt) {}

    public Result check(String domain) {
        Check spf = checkSpf(domain);
        Check dkim = checkDkim(domain);
        String dmarcPolicy = dmarcPolicy(domain);
        Check dmarc = checkDmarc(dmarcPolicy);
        Check mx = checkMx(domain);

        int score = (spf.pass() ? 25 : 0) + (dkim.pass() ? 25 : 0) + dmarcPoints(dmarcPolicy) + (mx.pass() ? 25 : 0);
        return new Result(domain, score, ratingFor(score), spf, dkim, dmarc, mx, Instant.now());
    }

    private Check checkSpf(String domain) {
        for (String value : dns.txt(domain)) {
            if (value.trim().toLowerCase(Locale.ROOT).startsWith("v=spf1")) {
                return new Check(true, value.trim());
            }
        }
        return new Check(false, "No SPF (v=spf1) record found on " + domain);
    }

    private Check checkDkim(String domain) {
        List<String> found = new ArrayList<>();
        for (DkimSelector sel : DKIM_SELECTORS) {
            String name = sel.host() + "." + domain;
            boolean present = sel.isCname() ? dns.cname(name).isPresent() : dkimTxtPresent(name);
            if (present) found.add(sel.provider() + " (" + sel.host() + ")");
        }
        if (found.isEmpty()) {
            return new Check(false, "No known DKIM selector found (checked Mailchimp k1/k2, Google Workspace)");
        }
        return new Check(true, "Found: " + String.join(", ", found));
    }

    private boolean dkimTxtPresent(String name) {
        // A single logical TXT value can be split across multiple on-wire character-strings (e.g.
        // a 2048-bit RSA key, like Google's) — nothing else legitimately shares this exact
        // selector hostname, so joining every returned value back together is always safe here,
        // unlike the root domain (see checkSpf) where several unrelated TXT records coexist.
        String joined = String.join("", dns.txt(name));
        return joined.trim().toLowerCase(Locale.ROOT).startsWith("v=dkim1");
    }

    /** {@code null} if no DMARC record exists at all. */
    private String dmarcPolicy(String domain) {
        for (String value : dns.txt("_dmarc." + domain)) {
            if (value.trim().toLowerCase(Locale.ROOT).startsWith("v=dmarc1")) {
                Matcher m = DMARC_POLICY.matcher(value);
                return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : "";
            }
        }
        return null;
    }

    private Check checkDmarc(String policy) {
        if (policy == null) return new Check(false, "No DMARC record found");
        if ("none".equals(policy)) return new Check(true, "p=none (published, but not enforced — monitoring only)");
        return new Check(true, "p=" + policy);
    }

    /** 0 if missing entirely, partial credit just for existing (even p=none still tells receivers
     * "this domain has an owner"), full credit once it's actually enforced. */
    private int dmarcPoints(String policy) {
        if (policy == null) return 0;
        if ("none".equals(policy)) return 10;
        return 25;
    }

    private Check checkMx(String domain) {
        List<String> hosts = dns.mxHosts(domain);
        if (hosts.isEmpty()) return new Check(false, "No MX records found for " + domain);
        List<String> dangling = hosts.stream().filter(h -> !dns.resolves(h)).toList();
        if (!dangling.isEmpty()) {
            return new Check(false, "MX target(s) with no A record (mail routed here will fail): " + String.join(", ", dangling));
        }
        return new Check(true, hosts.size() + " MX host(s), all resolve: " + String.join(", ", hosts));
    }

    private static String ratingFor(int score) {
        if (score >= 90) return "Excellent";
        if (score >= 70) return "Good";
        if (score >= 40) return "Needs work";
        return "Poor";
    }
}
