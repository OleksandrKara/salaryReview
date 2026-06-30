package com.salonreview.repo;

import com.salonreview.domain.RagSuggestionCache;
import org.springframework.data.jpa.repository.JpaRepository;

/** Durable per-language cache of starter prompts (id = "EN" / "RU"). */
public interface RagSuggestionCacheRepository extends JpaRepository<RagSuggestionCache, String> {
}
