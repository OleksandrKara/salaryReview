package com.salonreview.aivisibility;

import com.salonreview.domain.AiVisibilityCheck;
import com.salonreview.domain.AiVisibilityQuery;
import com.salonreview.repo.AiVisibilityCheckRepository;
import com.salonreview.repo.AiVisibilityQueryRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * "Questions the assistants ask themselves" (Notion #27, 2026-10-15): when ChatGPT, Gemini or
 * Google AI answer one of our tracked questions they run their own searches (sub_queries on every
 * check). The ones that keep coming back and don't just rephrase a question we already track are
 * candidates for new tracked questions ("bad microblading cover up", "beginner permanent makeup
 * course California requirements"). Read-only: the owner adds one with the normal add flow.
 */
@Service
public class AiQuestionSuggestionService {

    static final Duration WINDOW = Duration.ofDays(30);
    /** Token overlap (Jaccard) at or above this with a tracked question = just a rephrasing. */
    static final double SAME_QUESTION = 0.5;
    static final int LIMIT = 10;
    private static final Set<String> STOP = Set.of("the", "a", "an", "in", "of", "for", "to", "and", "or", "near", "me",
            "best", "top", "san", "diego", "ca", "california", "2025", "2026", "reviews", "review", "with", "at", "is", "what",
            "how", "who", "which", "my", "your", "do", "does", "are", "on");

    public record Suggestion(String text, int timesAsked, int fromQuestions, String exampleQuestion, String topic,
                             String targetUrl) {
    }

    private final AiVisibilityCheckRepository checks;
    private final AiVisibilityQueryRepository queries;

    public AiQuestionSuggestionService(AiVisibilityCheckRepository checks, AiVisibilityQueryRepository queries) {
        this.checks = checks;
        this.queries = queries;
    }

    public List<Suggestion> suggestions(Long businessId, String site) {
        List<AiVisibilityQuery> all = queries.findByBusinessIdAndStatusInOrderBySortOrderAscIdAsc(businessId,
                EnumSet.allOf(AiVisibilityQuery.Status.class));
        Map<Long, AiVisibilityQuery> byId = all.stream().collect(Collectors.toMap(AiVisibilityQuery::getId, q -> q));
        List<Set<String>> tracked = all.stream().map(q -> tokens(q.getQueryText())).toList();

        Map<String, List<AiVisibilityCheck>> bySub = new LinkedHashMap<>();
        Map<String, String> display = new HashMap<>();
        for (AiVisibilityCheck c : checks.findByBusinessIdAndCheckedAtAfterOrderByCheckedAtDesc(businessId, Instant.now().minus(WINDOW))) {
            AiVisibilityQuery parent = byId.get(c.getQueryId());
            if (parent == null || !AiVisibilityQueryService.inSite(site, parent)) continue;
            for (String s : c.getSubQueries()) {
                if (s == null) continue;
                String text = s.trim().replaceAll("\\s+", " ");
                if (text.length() < 8 || text.length() > 200) continue;
                String key = text.toLowerCase(Locale.ROOT);
                display.putIfAbsent(key, text);
                bySub.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
            }
        }

        List<Suggestion> out = new ArrayList<>();
        Set<Set<String>> seen = new HashSet<>();
        for (var e : bySub.entrySet()) {
            Set<String> t = tokens(e.getKey());
            if (t.isEmpty() || seen.contains(t)) continue;
            if (tracked.stream().anyMatch(q -> jaccard(q, t) >= SAME_QUESTION)) continue;
            seen.add(t);
            List<AiVisibilityCheck> cs = e.getValue();
            Map<Long, Long> perQuestion = cs.stream().collect(Collectors.groupingBy(AiVisibilityCheck::getQueryId, Collectors.counting()));
            AiVisibilityQuery top = byId.get(perQuestion.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey());
            out.add(new Suggestion(display.get(e.getKey()), cs.size(), perQuestion.size(), top.getQueryText(), top.getTopic(),
                    top.getTargetUrl()));
        }
        out.sort(Comparator.comparingInt(Suggestion::fromQuestions).reversed()
                .thenComparing(Comparator.comparingInt(Suggestion::timesAsked).reversed())
                .thenComparing(s -> s.text().length()));
        return out.subList(0, Math.min(LIMIT, out.size()));
    }

    static Set<String> tokens(String text) {
        if (text == null) return Set.of();
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(w -> w.length() > 1 && !STOP.contains(w))
                .map(w -> w.endsWith("s") && w.length() > 4 ? w.substring(0, w.length() - 1) : w)
                .collect(Collectors.toSet());
    }

    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
    }
}
