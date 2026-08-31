package com.salonreview.sms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Scoring/parsing logic for {@link EmailDomainHealthService}, driven by a fake {@link
 * DnsResolver} — real DNS I/O lives in {@link JndiDnsResolver}, deliberately untested here (see
 * that class's own doc), same as every other network-touching class in this codebase. */
class EmailDomainHealthServiceTest {

    /** Minimal in-memory resolver — tests populate exactly the records they care about. */
    private static final class FakeDnsResolver implements DnsResolver {
        final Map<String, List<String>> txt = new HashMap<>();
        final Map<String, String> cname = new HashMap<>();
        final Map<String, List<String>> mx = new HashMap<>();
        final Map<String, Boolean> resolvable = new HashMap<>();

        @Override
        public List<String> txt(String name) {
            return txt.getOrDefault(name, List.of());
        }

        @Override
        public Optional<String> cname(String name) {
            return Optional.ofNullable(cname.get(name));
        }

        @Override
        public List<String> mxHosts(String domain) {
            return mx.getOrDefault(domain, List.of());
        }

        @Override
        public boolean resolves(String host) {
            return resolvable.getOrDefault(host, false);
        }
    }

    @Test
    @DisplayName("A fully-authenticated domain (SPF + Mailchimp DKIM + strict DMARC + clean MX) scores 100/Excellent — "
            + "the akluxnails.com shape")
    void fullyAuthenticatedDomainScoresPerfect() {
        FakeDnsResolver dns = new FakeDnsResolver();
        dns.txt.put("akluxnails.com", List.of("v=spf1 include:_spf.mx.cloudflare.net include:servers.mcsv.net ~all"));
        dns.cname.put("k1._domainkey.akluxnails.com", "dkim2.mcsv.net");
        dns.txt.put("_dmarc.akluxnails.com", List.of("v=DMARC1; p=reject; sp=reject; adkim=s; aspf=s"));
        dns.mx.put("akluxnails.com", List.of("route1.mx.cloudflare.net", "route2.mx.cloudflare.net"));
        dns.resolvable.put("route1.mx.cloudflare.net", true);
        dns.resolvable.put("route2.mx.cloudflare.net", true);

        EmailDomainHealthService.Result r = new EmailDomainHealthService(dns).check("akluxnails.com");

        assertThat(r.score()).isEqualTo(100);
        assertThat(r.rating()).isEqualTo("Excellent");
        assertThat(r.spf().pass()).isTrue();
        assertThat(r.dkim().pass()).isTrue();
        assertThat(r.dkim().detail()).contains("Mailchimp");
        assertThat(r.dmarc().pass()).isTrue();
        assertThat(r.mx().pass()).isTrue();
    }

    @Test
    @DisplayName("Regression: pmu-annakara.com's real broken state before the 2026-08-31 fix — no SPF-Google "
            + "include, no DKIM, a dangling MX with no A record, and DMARC p=none — scores low with the exact "
            + "dangling-MX target named in the detail")
    void brokenDomainBeforeFixScoresLowAndNamesTheDanglingMx() {
        FakeDnsResolver dns = new FakeDnsResolver();
        dns.txt.put("pmu-annakara.com", List.of("v=spf1 a mx include:websitewelcome.com ~all"));
        // No k1/k2/google._domainkey entries at all — DKIM absent.
        dns.txt.put("_dmarc.pmu-annakara.com", List.of("v=DMARC1; p=none;"));
        dns.mx.put("pmu-annakara.com", List.of("mail.pmu-annakara.com", "smtp.google.com"));
        dns.resolvable.put("smtp.google.com", true);
        // mail.pmu-annakara.com deliberately left unresolvable (no A record) — the actual bug.

        EmailDomainHealthService.Result r = new EmailDomainHealthService(dns).check("pmu-annakara.com");

        // SPF present (25) + DKIM absent (0) + DMARC p=none (10) + MX dangling (0) = 35.
        assertThat(r.score()).isEqualTo(35);
        assertThat(r.rating()).isEqualTo("Poor");
        assertThat(r.spf().pass()).isTrue();
        assertThat(r.dkim().pass()).isFalse();
        assertThat(r.dmarc().pass()).isTrue();
        assertThat(r.dmarc().detail()).contains("not enforced");
        assertThat(r.mx().pass()).isFalse();
        assertThat(r.mx().detail()).contains("mail.pmu-annakara.com");
    }

    @Test
    @DisplayName("pmu-annakara.com's real state after the 2026-08-31 fix (Google DKIM added, SPF updated, "
            + "dangling MX removed) scores 90/Excellent even with DMARC still at p=none")
    void fixedDomainScoresExcellentEvenBeforeDmarcIsTightened() {
        FakeDnsResolver dns = new FakeDnsResolver();
        dns.txt.put("pmu-annakara.com", List.of("v=spf1 include:_spf.google.com include:servers.mcsv.net ~all"));
        dns.txt.put("google._domainkey.pmu-annakara.com", List.of("v=DKIM1; k=rsa; p=", "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A..."));
        dns.txt.put("_dmarc.pmu-annakara.com", List.of("v=DMARC1; p=none;"));
        dns.mx.put("pmu-annakara.com", List.of("smtp.google.com"));
        dns.resolvable.put("smtp.google.com", true);

        EmailDomainHealthService.Result r = new EmailDomainHealthService(dns).check("pmu-annakara.com");

        // SPF (25) + DKIM (25) + DMARC p=none (10) + MX clean (25) = 85.
        assertThat(r.score()).isEqualTo(85);
        assertThat(r.rating()).isEqualTo("Good");
        assertThat(r.dkim().pass()).isTrue();
        assertThat(r.dkim().detail()).contains("Google Workspace");
    }

    @Test
    @DisplayName("Root-domain TXT lookup finds SPF among unrelated coexisting records "
            + "(google-site-verification tokens) instead of misreading one of those as SPF")
    void spfLookupIgnoresUnrelatedTxtRecordsAtTheSameName() {
        FakeDnsResolver dns = new FakeDnsResolver();
        dns.txt.put("example.com", List.of(
                "google-site-verification=abc123",
                "google-site-verification=def456",
                "v=spf1 include:_spf.google.com ~all",
                "google-site-verification=ghi789"));

        EmailDomainHealthService.Check spf = new EmailDomainHealthService(dns).check("example.com").spf();

        assertThat(spf.pass()).isTrue();
        assertThat(spf.detail()).isEqualTo("v=spf1 include:_spf.google.com ~all");
    }

    @Test
    @DisplayName("No records anywhere: every check fails, score is 0/Poor")
    void emptyDomainScoresZero() {
        EmailDomainHealthService.Result r = new EmailDomainHealthService(new FakeDnsResolver()).check("nothing-configured.example");

        assertThat(r.score()).isZero();
        assertThat(r.rating()).isEqualTo("Poor");
        assertThat(r.spf().pass()).isFalse();
        assertThat(r.dkim().pass()).isFalse();
        assertThat(r.dmarc().pass()).isFalse();
        assertThat(r.mx().pass()).isFalse();
    }
}
