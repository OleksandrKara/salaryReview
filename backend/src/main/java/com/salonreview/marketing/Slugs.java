package com.salonreview.marketing;

import java.util.regex.Pattern;

/** Mirrors salonLandings' Python slugify() so a variant's deep-link key stays consistent
 * with how the CLI generates one, e.g. "Holiday Gold!" -> "holiday-gold".
 */
final class Slugs {

    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");

    private Slugs() {}

    static String slugify(String name) {
        String slug = NON_ALNUM.matcher(name.trim().toLowerCase()).replaceAll("-");
        slug = slug.replaceAll("^-+", "").replaceAll("-+$", "");
        return slug.isEmpty() ? "variant" : slug;
    }
}
