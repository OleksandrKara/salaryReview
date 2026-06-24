package com.salonreview.rag;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Structure-aware text splitter. Splits on blank-line (paragraph) boundaries rather than blind
 * fixed-size cuts — SOPs are organised by heading/paragraph, so respecting those boundaries keeps
 * each chunk semantically coherent (and therefore cleanly embeddable). Paragraphs are greedily
 * packed up to {@link #MAX_CHARS}, and each chunk re-includes a trailing overlap (~15%) of the
 * previous one so a fact straddling a boundary stays retrievable from both sides.
 *
 * <p>Char budgets approximate tokens (~4 chars/token): ~3000 chars ≈ ~750 tokens, the empirical
 * sweet spot for prose retrieval.
 */
@Component
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true")
public class Chunker {

    static final int MAX_CHARS = 3000;
    static final int OVERLAP_CHARS = 450;

    public List<Chunk> chunk(String text) {
        List<int[]> paragraphs = paragraphSpans(text);
        List<Chunk> chunks = new ArrayList<>();
        if (paragraphs.isEmpty()) return chunks;

        int i = 0;
        while (i < paragraphs.size()) {
            int start = paragraphs.get(i)[0];
            int end = paragraphs.get(i)[1];
            int j = i + 1;
            // Greedily extend while we stay under the budget (always take at least one paragraph).
            while (j < paragraphs.size() && (paragraphs.get(j)[1] - start) <= MAX_CHARS) {
                end = paragraphs.get(j)[1];
                j++;
            }
            chunks.add(new Chunk(text.substring(start, end), start, end));

            if (j >= paragraphs.size()) break;

            // Overlap: rewind the start of the next chunk to include trailing paragraphs of this one
            // whose combined length is within the overlap budget.
            int k = j;
            while (k > i + 1 && (end - paragraphs.get(k - 1)[0]) <= OVERLAP_CHARS) {
                k--;
            }
            i = Math.max(k, i + 1); // always make progress
        }
        return chunks;
    }

    /** Character spans [start, end) of paragraphs, split on runs of blank lines. */
    private static List<int[]> paragraphSpans(String text) {
        List<int[]> spans = new ArrayList<>();
        int n = text.length();
        int i = 0;
        while (i < n) {
            // Skip leading whitespace/blank lines.
            while (i < n && Character.isWhitespace(text.charAt(i))) i++;
            if (i >= n) break;
            int start = i;
            // Advance to a blank-line boundary (\n followed by optional spaces then \n) or EOF.
            int end = n;
            for (int p = i; p < n; p++) {
                if (text.charAt(p) == '\n') {
                    int q = p + 1;
                    while (q < n && (text.charAt(q) == ' ' || text.charAt(q) == '\t' || text.charAt(q) == '\r')) q++;
                    if (q < n && text.charAt(q) == '\n') {
                        end = p;
                        break;
                    }
                }
            }
            // Trim trailing whitespace of the paragraph.
            int realEnd = end;
            while (realEnd > start && Character.isWhitespace(text.charAt(realEnd - 1))) realEnd--;
            if (realEnd > start) spans.add(new int[]{start, realEnd});
            i = end + 1;
        }
        return spans;
    }
}
