package com.salonreview.landing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Resolves which landing-page variants a visitor may see, from the marketing schema. */
@Service
public class ExperimentLandingService {

    private static final Logger log = LoggerFactory.getLogger(ExperimentLandingService.class);
    private static final String VARIANT_COLUMNS =
            "SELECT id::text AS id, name, weight, content::text AS content FROM marketing.landing_variants ";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public ExperimentLandingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> resolve(String slug, String variantKey) {
        try {
            return doResolve(slug, variantKey);
        } catch (Exception e) {
            // Must never break page load: callers fall back to their built-in content.
            log.warn("Failed to resolve experiment for slug={}", slug, e);
            return body(null, "none", List.of());
        }
    }

    private Map<String, Object> doResolve(String slug, String variantKey) throws Exception {
        List<String> pages = jdbc.queryForList("SELECT id::text FROM marketing.landing_pages WHERE slug = ?", String.class, slug);
        if (pages.isEmpty()) return body(null, "none", List.of());
        String pageId = pages.get(0);

        if (variantKey != null && !variantKey.isEmpty()) {
            List<Map<String, Object>> forced = jdbc.queryForList(VARIANT_COLUMNS
                    + "WHERE landing_page_id = ?::uuid AND key = ? AND active = true", pageId, variantKey);
            if (!forced.isEmpty()) return body(pageId, "forced", List.of(variant(forced.get(0))));
        }

        List<String> experiments = jdbc.queryForList(
                "SELECT status FROM marketing.experiments WHERE landing_page_id = ?::uuid AND status = 'active'",
                String.class, pageId);
        List<Map<String, Object>> eligible = jdbc.queryForList(VARIANT_COLUMNS
                + "WHERE landing_page_id = ?::uuid AND active = true AND weight > 0 ORDER BY created_at ASC", pageId);
        if (!experiments.isEmpty() && !eligible.isEmpty()) {
            List<Map<String, Object>> variants = new ArrayList<>();
            for (Map<String, Object> row : eligible) variants.add(variant(row));
            return body(pageId, "active", variants);
        }
        List<Map<String, Object>> fallback = eligible.isEmpty() ? List.of() : List.of(variant(eligible.get(0)));
        return body(pageId, experiments.isEmpty() ? "none" : experiments.get(0), fallback);
    }

    private Map<String, Object> variant(Map<String, Object> row) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.get("id"));
        m.put("name", row.get("name"));
        m.put("weight", row.get("weight"));
        Object content = row.get("content");
        JsonNode parsed = content == null ? null : mapper.readTree(content.toString());
        m.put("content", parsed);
        return m;
    }

    private static Map<String, Object> body(String pageId, String status, List<Map<String, Object>> variants) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("landing_page_id", pageId);
        m.put("experiment_status", status);
        m.put("variants", variants);
        return m;
    }
}
