package com.salonreview.domain;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** Composite key for {@link RagSuggestionCache} — one cached starter-prompt set per (business, language). */
@EqualsAndHashCode
@NoArgsConstructor @AllArgsConstructor
public class RagSuggestionCacheId implements Serializable {
    private Long businessId;
    private String language;
}
