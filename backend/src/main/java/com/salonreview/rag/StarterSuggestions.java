package com.salonreview.rag;

import java.util.List;

/**
 * Starter prompts offered in the chat widget's empty state, grouped into a few topic labels. Built
 * from the indexed corpus so every suggestion is answerable. Also the structured-output shape the
 * model fills in.
 */
public record StarterSuggestions(List<Topic> topics) {

    public record Topic(String label, List<String> questions) {}

    public static StarterSuggestions empty() {
        return new StarterSuggestions(List.of());
    }
}
