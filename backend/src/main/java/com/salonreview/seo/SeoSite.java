package com.salonreview.seo;

import java.net.URI;
import java.util.Set;

/** One business, two sites on one Search Console property (2026-10-14): the PMU studio and the
 * PMU Academy (pmu-annakara.com/academy/, formerly edu.pmu-annakara.com, 301'd to /academy/).
 * Lets the SEO tab show either one apart from the other. */
public final class SeoSite {

    public static final String ALL = "all";
    public static final String STUDIO = "studio";
    public static final String ACADEMY = "academy";
    public static final Set<String> VALUES = Set.of(ALL, STUDIO, ACADEMY);

    private SeoSite() {
    }

    public static boolean isAll(String site) {
        return site == null || site.isBlank() || ALL.equals(site);
    }

    /** Whether {@code url} (a page URL, or null) belongs to {@code site}. */
    public static boolean matches(String site, String url) {
        if (isAll(site)) return true;
        boolean academy = isAcademyUrl(url);
        return ACADEMY.equals(site) ? academy : !academy;
    }

    public static boolean isAcademyUrl(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            URI u = URI.create(url.trim());
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase();
            String path = u.getHost() == null ? url.trim() : (u.getPath() == null ? "" : u.getPath());
            return host.startsWith("edu.") || path.equals("/academy") || path.startsWith("/academy/");
        } catch (IllegalArgumentException e) {
            return url.contains("/academy");
        }
    }
}
