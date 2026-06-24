package com.salonreview.rag;

import java.util.List;

/**
 * Structured-output shape for the per-chunk safety gate. The Anthropic SDK derives a JSON schema
 * from this record and constrains the model to it.
 *
 * @param containsPii true if the chunk contains personally identifiable information
 * @param piiTypes    the kinds found (e.g. "email", "phone", "person_name"); empty when none
 * @param relevance   "RELEVANT" or "IRRELEVANT" to a salon operations/policy knowledge base
 * @param reason      one short sentence explaining the decision
 */
public record ChunkClassification(
        boolean containsPii,
        List<String> piiTypes,
        String relevance,
        String reason) {

    public boolean isQuarantined() {
        return containsPii || !"RELEVANT".equalsIgnoreCase(relevance);
    }

    /** Compact reason string stored on a quarantined chunk. */
    public String quarantineReason() {
        if (containsPii) {
            String types = (piiTypes == null || piiTypes.isEmpty()) ? "" : String.join(",", piiTypes);
            return "pii:" + types;
        }
        return "irrelevant";
    }
}
