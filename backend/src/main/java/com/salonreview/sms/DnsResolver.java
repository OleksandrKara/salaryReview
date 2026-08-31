package com.salonreview.sms;

import java.util.List;
import java.util.Optional;

/** Thin seam over DNS lookups so {@link EmailDomainHealthService}'s actual scoring/parsing logic
 * can be unit-tested with fake records instead of hitting real DNS from CI — same reasoning every
 * other network-touching class in this codebase (e.g. {@code TwilioSmsClient}) gets tested
 * indirectly via a mock of itself rather than a live call. See {@link JndiDnsResolver} for the
 * real implementation. */
public interface DnsResolver {

    /** Every distinct TXT value at {@code name}, quotes stripped — a name can legitimately host
     * several unrelated TXT records at once (e.g. an SPF record alongside Google
     * site-verification tokens), so callers search this list for the prefix they care about
     * rather than assuming there's only one. Empty list if the name has none (or doesn't exist). */
    List<String> txt(String name);

    Optional<String> cname(String name);

    /** Host names only (MX priority prefix and trailing root dot already stripped). */
    List<String> mxHosts(String domain);

    /** True if {@code host} resolves to at least one A or AAAA record. */
    boolean resolves(String host);
}
