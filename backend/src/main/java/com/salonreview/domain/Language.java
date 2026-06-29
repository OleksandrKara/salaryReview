package com.salonreview.domain;

/**
 * A user's preferred UI/content language. English is the default and the fallback: when a piece of
 * content (RAG answer, KB article, SOP) has no Russian version, the English one is shown.
 */
public enum Language {
    EN, RU
}
