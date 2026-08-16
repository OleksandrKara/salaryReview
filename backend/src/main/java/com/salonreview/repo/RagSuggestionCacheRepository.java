package com.salonreview.repo;

import com.salonreview.domain.RagSuggestionCache;
import com.salonreview.domain.RagSuggestionCacheId;
import org.springframework.data.jpa.repository.JpaRepository;

/** Durable per-(business, language) cache of starter prompts. */
public interface RagSuggestionCacheRepository extends JpaRepository<RagSuggestionCache, RagSuggestionCacheId> {
}
