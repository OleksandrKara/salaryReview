package com.salonreview.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.config.RagProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Thin HTTPS client for the Voyage AI embeddings API. Anthropic has no embeddings endpoint, so the
 * RAG retrieval half is built on Voyage (Anthropic's own recommendation). Hand-rolled like
 * {@link com.salonreview.ai.LangSmithClient} rather than pulling in a framework — see openspec
 * design D5.
 *
 * <p>Unlike the LangSmith tracer (best-effort), embedding failures are NOT swallowed: if we can't
 * embed, ingestion/retrieval cannot proceed, so callers get an exception.
 *
 * <p>Registered only when {@code rag.enabled=true}.
 */
@Component
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true")
public class VoyageClient {

    private static final String URL = "https://api.voyageai.com/v1/embeddings";

    /** voyage-3.5 at 1024 dims — must match the {@code vector(1024)} column in V24. */
    static final String MODEL = "voyage-3.5";
    static final int DIMENSIONS = 1024;

    private final RagProperties props;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public VoyageClient(RagProperties props) {
        // Fail fast at startup (this bean only exists when rag.enabled=true) — mirrors
        // AnthropicClientConfig. Better a clear boot error than a runtime surprise on first embed.
        if (!props.isVoyageConfigured()) {
            throw new IllegalStateException(
                    "rag.enabled=true but VOYAGE_API_KEY is missing. "
                            + "Either set the key (see .env.example) or set RAG_ENABLED=false.");
        }
        this.props = props;
    }

    /** Embed a document chunk for storage. Voyage recommends input_type=document at ingest time. */
    public float[] embedDocument(String text) {
        return embed(text, "document");
    }

    /** Embed a user question for search. Voyage recommends input_type=query at retrieval time. */
    public float[] embedQuery(String text) {
        return embed(text, "query");
    }

    private float[] embed(String text, String inputType) {
        if (!props.isVoyageConfigured()) {
            throw new IllegalStateException("VOYAGE_API_KEY is missing — cannot embed.");
        }
        try {
            Map<String, Object> body = Map.of(
                    "input", List.of(text),
                    "model", MODEL,
                    "input_type", inputType,
                    "output_dimension", DIMENSIONS);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(URL))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + props.getVoyageApiKey())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                throw new IllegalStateException("Voyage embeddings returned " + res.statusCode() + ": " + res.body());
            }
            JsonNode embedding = json.readTree(res.body()).path("data").path(0).path("embedding");
            if (!embedding.isArray() || embedding.size() != DIMENSIONS) {
                throw new IllegalStateException("Voyage returned an unexpected embedding shape");
            }
            float[] vec = new float[embedding.size()];
            for (int i = 0; i < vec.length; i++) {
                vec[i] = (float) embedding.get(i).asDouble();
            }
            return vec;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Voyage embedding call failed", e);
        }
    }

    /** Format an embedding as a pgvector literal: {@code [0.1,0.2,...]}. */
    public static String toVectorLiteral(float[] vec) {
        StringBuilder sb = new StringBuilder(vec.length * 8).append('[');
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        return sb.append(']').toString();
    }
}
