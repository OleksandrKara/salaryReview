package com.salonreview.aivisibility;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Decides whether an answer recommended the business (design.md D4). Aliases match as whole
 * token sequences, ignoring case and punctuation ("AK.LUX.NAILS" ~ "Ak Lux Nails" ~ "AKLuxNails"),
 * so "Anna's Nails" never counts as "Anna Kara". Own domains match by host suffix, in the text or in
 * the citations. */
public final class MentionDetector {

    public record Result(boolean mentioned, Short rank, boolean ownSiteCited, String excerpt) {
    }

    static final int EXCERPT_CHARS = 1500;

    private MentionDetector() {
    }

    public static Result detect(ParsedAnswer answer, List<String> aliases, List<String> ownDomains) {
        boolean cited = answer.citations().stream().anyMatch(c -> isOwnDomain(c.domain(), ownDomains));
        String lowerText = answer.text().toLowerCase(Locale.ROOT);
        boolean domainInText = ownDomains.stream().anyMatch(d -> lowerText.contains(d.toLowerCase(Locale.ROOT)));
        boolean aliasInText = containsAlias(answer.text(), aliases);
        boolean mentioned = aliasInText || domainInText || cited;

        Short rank = null;
        List<String> places = answer.places();
        for (int i = 0; i < places.size(); i++) {
            if (containsAlias(places.get(i), aliases)) { rank = (short) (i + 1); break; }
        }
        return new Result(mentioned, rank, cited, excerpt(answer.text(), aliases, mentioned));
    }

    static boolean isOwnDomain(String domain, List<String> ownDomains) {
        if (domain == null) return false;
        String d = domain.toLowerCase(Locale.ROOT);
        for (String own : ownDomains) {
            String o = own.toLowerCase(Locale.ROOT);
            if (d.equals(o) || d.endsWith("." + o)) return true;
        }
        return false;
    }

    static boolean containsAlias(String text, List<String> aliases) {
        return firstAliasTokenIndex(tokens(text), aliases) >= 0;
    }

    private static int firstAliasTokenIndex(List<String> textTokens, List<String> aliases) {
        int best = -1;
        for (String alias : aliases) {
            List<String> a = tokens(alias);
            if (a.isEmpty()) continue;
            String joined = String.join("", a);
            for (int i = 0; i < textTokens.size(); i++) {
                boolean seq = i + a.size() <= textTokens.size() && textTokens.subList(i, i + a.size()).equals(a);
                boolean squashed = a.size() > 1 && textTokens.get(i).equals(joined);
                if (seq || squashed) {
                    if (best < 0 || i < best) best = i;
                    break;
                }
            }
        }
        return best;
    }

    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        for (String t : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) if (!t.isEmpty()) out.add(t);
        return out;
    }

    private static String excerpt(String text, List<String> aliases, boolean mentioned) {
        if (text.length() <= EXCERPT_CHARS) return text;
        int start = 0;
        if (mentioned) {
            // Rough char position of the first alias token, so the excerpt shows the mention.
            String lower = text.toLowerCase(Locale.ROOT);
            int pos = -1;
            for (String alias : aliases) {
                List<String> a = tokens(alias);
                if (a.isEmpty()) continue;
                int p = lower.indexOf(a.get(0));
                while (p >= 0) {
                    String window = lower.substring(p, Math.min(lower.length(), p + alias.length() + 8));
                    if (containsAlias(window, List.of(alias))) break;
                    p = lower.indexOf(a.get(0), p + 1);
                }
                if (p >= 0 && (pos < 0 || p < pos)) pos = p;
            }
            if (pos > EXCERPT_CHARS - 300) start = Math.max(0, pos - 400);
        }
        int end = Math.min(text.length(), start + EXCERPT_CHARS);
        return (start > 0 ? "…" : "") + text.substring(start, end) + (end < text.length() ? "…" : "");
    }
}
