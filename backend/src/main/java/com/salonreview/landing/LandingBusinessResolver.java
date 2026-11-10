package com.salonreview.landing;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.salonreview.domain.Business;
import com.salonreview.repo.BusinessRepository;

/** Maps the request Host header to a business for the public landing endpoints. */
@Component
public class LandingBusinessResolver {

    public record BusinessContext(long id, String name, String timezone) {
    }

    static final BusinessContext LEGACY = new BusinessContext(1L, "AK.LUX.NAILS", "America/Los_Angeles");
    private static final long TTL_NANOS = Duration.ofSeconds(300).toNanos();

    private record Entry(BusinessContext business, long expiresAtNanos) {
    }

    private final BusinessRepository businesses;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public LandingBusinessResolver(BusinessRepository businesses) {
        this.businesses = businesses;
    }

    public static String hostOf(String hostHeader) {
        if (hostHeader == null) return "";
        int colon = hostHeader.indexOf(':');
        String host = colon >= 0 ? hostHeader.substring(0, colon) : hostHeader;
        return host.trim().toLowerCase();
    }

    public BusinessContext resolve(String hostHeader) {
        String host = hostOf(hostHeader);
        Entry cached = cache.get(host);
        long now = System.nanoTime();
        if (cached != null && cached.expiresAtNanos() - now > 0) {
            return cached.business();
        }
        try {
            Business business = businesses.findByPublicDomain(host).orElse(null);
            if (business != null) {
                BusinessContext ctx = new BusinessContext(business.getId(), business.getName(), business.getTimezone());
                cache.put(host, new Entry(ctx, now + TTL_NANOS));
                return ctx;
            }
        } catch (RuntimeException e) {
            // unknown domain or lookup failure: same fallback either way
        }
        return LEGACY;
    }
}
