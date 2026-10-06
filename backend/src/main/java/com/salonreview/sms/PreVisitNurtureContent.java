package com.salonreview.sms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Studio and artist facts for the PMU pre-consultation emails (owner request 2026-10-05), kept as
 * a JSON resource next to the templates ({@code email-templates/business-N/pre_visit_nurture_content.json})
 * so the copy about each artist can change without touching Java. Empty for a business without
 * the file (business 1's generic welcome/reminder pair needs none of it).
 */
@Component
public class PreVisitNurtureContent {

    public record Studio(String name, String address, String textNumber) {}

    /** Square service variation used to look up an artist's real openings (consultation
     * follow-up's day-21 text): a procedure every artist does, so its slots reflect a real
     * procedure-length opening. Empty when not configured. */
    public Optional<String> availabilityVariationId(Long businessId) {
        return load(businessId).map(n -> n.path("availabilityVariationId").asText("")).filter(v -> !v.isBlank());
    }

    public record Artist(String photoUrl, String headline, String bio, String quote, String quoteAuthor) {}

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<Long, Optional<JsonNode>> cache = new ConcurrentHashMap<>();

    public Optional<Studio> studio(Long businessId) {
        return load(businessId).map(n -> new Studio(
                n.path("studioName").asText(""), n.path("studioAddress").asText(""), n.path("textNumber").asText("")));
    }

    /** The named artist's profile, or the studio-wide default when the booking's artist has no
     * entry (or is unknown). */
    public Optional<Artist> artist(Long businessId, String firstName) {
        return load(businessId).map(n -> {
            JsonNode a = firstName == null ? null : n.path("artists").get(firstName);
            return toArtist(a != null ? a : n.path("defaultArtist"));
        });
    }

    private static Artist toArtist(JsonNode a) {
        return new Artist(a.path("photoUrl").asText(""), a.path("headline").asText(""), a.path("bio").asText(""),
                a.path("quote").asText(""), a.path("quoteAuthor").asText(""));
    }

    private Optional<JsonNode> load(Long businessId) {
        return cache.computeIfAbsent(businessId, id -> {
            ClassPathResource resource = new ClassPathResource(
                    "email-templates/business-" + id + "/pre_visit_nurture_content.json");
            if (!resource.exists()) {
                return Optional.empty();
            }
            try (InputStream in = resource.getInputStream()) {
                return Optional.of(mapper.readTree(in));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to load " + resource.getPath(), e);
            }
        });
    }
}
