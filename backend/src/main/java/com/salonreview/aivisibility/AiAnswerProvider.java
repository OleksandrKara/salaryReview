package com.salonreview.aivisibility;

import com.fasterxml.jackson.databind.JsonNode;
import com.salonreview.domain.AiVisibilityCheck.Engine;

import java.math.BigDecimal;

/** Asks one question to one AI engine. The only implementation calls DataForSEO
 * ({@link DataForSeoAnswerProvider}); tests stub this interface so no run ever touches the network. */
public interface AiAnswerProvider {

    /** {@code result} is the provider's task result object (the part the parser reads); null when the
     * engine returned nothing for the question. Throws on a failed call after retries. */
    record RawAnswer(JsonNode result, BigDecimal cost, String model) {
    }

    /** {@code coordinate} ("lat,lng,zoom") is used by the Google engines when set, else
     * {@code locationName}; the LLM engines take location from the question itself. */
    RawAnswer ask(Engine engine, String question, String locationName, String coordinate);
}
