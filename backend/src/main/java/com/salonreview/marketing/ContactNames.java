package com.salonreview.marketing;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Name hygiene shared by every surface that shows or uses a client's name. */
public final class ContactNames {

    private static final Pattern JUNK_TOKEN = Pattern.compile(
            "(?iu)^(tests?|testing|asdf\\w*|qwe\\w*|xxx+|aaa+|abc|name|none|null|nan|n/?a|unknown|noname|admin|user|guest|customer|client|[\\p{Punct}\\d\\s]*)$");

    private ContactNames() {}

    /** True for placeholders typed into forms ("Test", "asdf", "-", digits or a phone number), or nothing at all. */
    public static boolean isJunk(String name) {
        if (name == null || name.isBlank()) return true;
        for (String token : name.trim().split("\\s+")) {
            if (!JUNK_TOKEN.matcher(token).matches()) return false;
        }
        return true;
    }

    public static String clean(String name) {
        return isJunk(name) ? null : name.trim().replaceAll("\\s+", " ");
    }

    public static String key(String name) {
        if (name == null) return "";
        String s = Normalizer.normalize(name.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** Same person written differently (case, accents, only the first name, a short form of the same first name). */
    public static boolean compatible(String a, String b) {
        String ka = key(a);
        String kb = key(b);
        if (ka.isEmpty() || kb.isEmpty() || ka.equals(kb)) return true;
        String fa = ka.split(" ")[0];
        String fb = kb.split(" ")[0];
        if (fa.equals(fb)) return true;
        int min = Math.min(fa.length(), fb.length());
        return min >= 3 && (fa.startsWith(fb) || fb.startsWith(fa));
    }

    public static String join(String given, String family) {
        String g = given == null ? "" : given.trim();
        String f = family == null ? "" : family.trim();
        return (g + " " + f).trim();
    }
}
