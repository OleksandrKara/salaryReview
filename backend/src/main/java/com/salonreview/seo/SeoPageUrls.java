package com.salonreview.seo;

import java.net.URI;

/** Normalization for page URLs coming from Search Console, shared by the sync and the page
 * analysis so one real page is never counted as several. */
public final class SeoPageUrls {

    private SeoPageUrls() {
    }

    /** Search Console reports anchors ({@code /#faq}) as separate pages; they are the same page. */
    public static String withoutFragment(String page) {
        if (page == null) return null;
        int hash = page.indexOf('#');
        return hash < 0 ? page : page.substring(0, hash);
    }

    /** The old Academy host is 301'd to /academy/, so its pages fading out is a move, not a loss. */
    public static boolean isRetiredHost(String page) {
        if (page == null) return false;
        try {
            String host = URI.create(page.trim()).getHost();
            return host != null && host.toLowerCase().startsWith("edu.");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
