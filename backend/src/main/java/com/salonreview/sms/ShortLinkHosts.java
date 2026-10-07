package com.salonreview.sms;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Which host a business's click-tracked short links (/r/{token}) use (owner request 2026-10-07):
 * a PMU client got review links on salon.akluxnails.com, the nail salon's domain, which reads like
 * spam in a text from a permanent makeup studio. {@code app.short-link-hosts} maps business id to
 * a host serving only /r/ (nginx), e.g. {@code 2=https://go.pmu-annakara.com}; a business without
 * an entry keeps {@code app.public-base-url}. ShortLinkController resolves a token on any host, so
 * links already sent keep working.
 */
@Component
public class ShortLinkHosts {

    private final String defaultBase;
    private final Map<Long, String> hosts = new HashMap<>();

    public ShortLinkHosts(@Value("${app.public-base-url}") String defaultBase,
                          @Value("${app.short-link-hosts:}") String spec) {
        this.defaultBase = defaultBase;
        for (String part : spec.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && !kv[0].isBlank() && !kv[1].isBlank()) {
                hosts.put(Long.valueOf(kv[0].trim()), kv[1].trim().replaceAll("/+$", ""));
            }
        }
    }

    public String shortLink(Long businessId, String token) {
        return hosts.getOrDefault(businessId, defaultBase) + "/r/" + token;
    }
}
