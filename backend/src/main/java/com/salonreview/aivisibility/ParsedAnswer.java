package com.salonreview.aivisibility;

import java.util.List;

/** Engine-independent shape of one AI answer (design.md D4). {@code places} are business names in
 * answer order; {@code citations} are the sources the engine showed. */
public record ParsedAnswer(String text, List<Citation> citations, List<String> places, List<String> subQueries) {

    public record Citation(String domain, String title, String url) {
    }

    public boolean isEmpty() {
        return text == null || text.isBlank();
    }
}
