package com.salonreview.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Durable cache row for the chat's grounded starter prompts, one per language. The {@code payload} is
 * a serialized {@code StarterSuggestions}; {@code signature} fingerprints the corpus so it invalidates
 * when documents change; {@code generatedAt} drives the 24h TTL. Keyed by language so EN and RU each
 * cache independently and are shared by all users.
 */
@Entity
@Table(name = "rag_suggestion_cache")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class RagSuggestionCache {

    /** "EN" or "RU". */
    @Id
    @Column(length = 8)
    private String language;

    @Column(nullable = false, columnDefinition = "text")
    private String signature;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;
}
