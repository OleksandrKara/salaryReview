package com.salonreview.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.salonreview.config.AiTriageProperties;
import com.salonreview.domain.SuspiciousTriage;
import com.salonreview.domain.TriageClassification;
import com.salonreview.repo.SuspiciousTriageRepository;
import com.salonreview.square.SquareMonthAggregator;
import com.salonreview.square.SuspiciousBookingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Owner-facing AI triage for flagged suspicious bookings. Looks up the cached triage first; on a
 * cache miss, calls Claude (Haiku 4.5) with prompt caching on the system prompt and structured
 * outputs constraining the response to {@link TriageResult}. Owner-driven, on-demand only — never
 * a batch job.
 *
 * <p>The bean is registered unconditionally so callers don't need ObjectProvider plumbing. When
 * the feature flag is off, every call returns {@code Optional.empty()} without touching the LLM.
 *
 * <p>See {@code openspec/changes/suspicious-booking-ai-triage/design.md} for the full rationale.
 */
@Service
public class SuspiciousBookingTriageService {

    private static final Logger log = LoggerFactory.getLogger(SuspiciousBookingTriageService.class);

    /** Model identifier. String form (rather than the SDK's typed Model enum) tolerates SDK version drift. */
    static final String MODEL = "claude-haiku-4-5";

    /** Max output tokens. ~400 tokens of structured JSON output is plenty for our schema. */
    private static final long MAX_OUTPUT_TOKENS = 512L;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("h:mm a", Locale.US);
    private static final DateTimeFormatter DAY_FMT  = DateTimeFormatter.ofPattern("EEEE", Locale.US);

    private final ObjectProvider<AnthropicClient> anthropicClientProvider;
    private final AiTriageProperties props;
    private final SuspiciousTriageRepository triages;
    private final SuspiciousBookingService suspiciousBookings;
    private final ObjectProvider<LangSmithTracer> tracerProvider;

    public SuspiciousBookingTriageService(ObjectProvider<AnthropicClient> anthropicClientProvider,
                                          AiTriageProperties props,
                                          SuspiciousTriageRepository triages,
                                          SuspiciousBookingService suspiciousBookings,
                                          ObjectProvider<LangSmithTracer> tracerProvider) {
        this.anthropicClientProvider = anthropicClientProvider;
        this.props = props;
        this.triages = triages;
        this.suspiciousBookings = suspiciousBookings;
        this.tracerProvider = tracerProvider;
    }

    /**
     * Triage a flagged booking. Returns the cached result on hit, the freshly-computed result on
     * miss, or {@link Optional#empty()} when the booking isn't flagged (→ 404) or the feature is
     * off.
     *
     * @throws TriageFailedException on Anthropic API errors or schema-validation failures —
     *         translated to 502 by the controller's exception handler.
     */
    @Transactional
    public Optional<TriageResult> triage(String bookingId, int year, int month) {
        if (!props.isEnabled()) return Optional.empty();
        AnthropicClient client = anthropicClientProvider.getIfAvailable();
        if (client == null) return Optional.empty();

        // 1. Cache lookup — repeat clicks return the cached row, no LLM call.
        Optional<SuspiciousTriage> cached =
                triages.findBySquareBookingIdAndPromptVersion(bookingId, TriagePrompts.PROMPT_VERSION);
        if (cached.isPresent()) {
            return Optional.of(toResult(cached.get()));
        }

        // 2. Verify the booking is currently flagged + grab its context (timezone, signals, notes).
        Optional<SuspiciousBookingService.CandidateLookup> lookup =
                suspiciousBookings.findCandidateForTriage(year, month, bookingId);
        if (lookup.isEmpty()) return Optional.empty(); // → 404

        SquareMonthAggregator.SuspiciousCandidate candidate = lookup.get().candidate();
        String tz = lookup.get().timezone();
        String serviceName = lookup.get().serviceName();
        List<String> signals = computeSignals(candidate, tz, serviceName);
        String userMessage = buildUserMessage(candidate, tz, serviceName, signals);

        // 3. Open the LangSmith trace (skipped when the tracer bean isn't registered).
        LangSmithTracer tracer = tracerProvider.getIfAvailable();
        LangSmithTracer.Trace trace = (tracer == null)
                ? null
                : tracer.startTrace("suspicious-booking-triage",
                        tagsFor(bookingId, candidate),
                        Map.of("user_message", userMessage));
        String runId = (trace == null) ? null : trace.runId();

        // 4. Call Claude, with structured-output enforcement + failure-mode handling.
        TriageResult result;
        String refusalCategory = null;
        try {
            result = callClaude(client, userMessage);
            if (trace != null) trace.complete(toOutputs(result), null, null);
        } catch (RefusalException re) {
            refusalCategory = re.category();
            result = refusalFallback(re.category());
            if (trace != null) trace.complete(null, null, "refusal:" + re.category());
        } catch (Exception e) {
            log.error("Claude triage failed for booking {}: {}", bookingId, e.toString());
            if (trace != null) trace.complete(null, null, e.toString());
            throw new TriageFailedException("LLM call failed", e);
        }

        // 5. Persist the result so the next click is free.
        SuspiciousTriage saved = persist(bookingId, result, refusalCategory, runId);
        return Optional.of(toResult(saved));
    }

    private static Map<String, String> tagsFor(String bookingId,
                                               SquareMonthAggregator.SuspiciousCandidate candidate) {
        Map<String, String> tags = new HashMap<>();
        tags.put("bookingId", bookingId);
        tags.put("providerId", candidate.providerId() == null ? "" : candidate.providerId());
        tags.put("promptVersion", TriagePrompts.PROMPT_VERSION);
        tags.put("model", MODEL);
        return tags;
    }

    /**
     * Record the owner's explicit feedback (thumbs-up / thumbs-down with optional correction) on
     * the current-prompt-version triage for a booking. Updates the DB row and ships a LangSmith
     * feedback event linked to the original trace. Returns false when no triage row exists for
     * this booking under the current prompt version (→ 404 in the controller).
     */
    @Transactional
    public boolean recordFeedback(String bookingId, boolean helpful, TriageClassification corrected) {
        return triages.findBySquareBookingIdAndPromptVersion(bookingId, TriagePrompts.PROMPT_VERSION)
                .map(t -> {
                    triages.updateFeedback(t.getId(), helpful, corrected);
                    tracerProvider.ifAvailable(tracer -> {
                        Map<String, Object> metadata = new HashMap<>();
                        metadata.put("owner_action", helpful ? "thumbs_up" : "thumbs_down");
                        metadata.put("llm_classification", t.getClassification().name());
                        if (corrected != null) metadata.put("corrected_classification", corrected.name());
                        tracer.feedback(t.getLangsmithRunId(), helpful ? 1.0 : 0.0,
                                "owner_explicit_feedback", metadata);
                    });
                    return true;
                })
                .orElse(false);
    }

    // ---------------------------------------------------------------------- internals

    /**
     * Single Claude call with structured-output enforcement. The SDK derives a JSON Schema from
     * {@link TriageResult}'s shape and constrains the model — no JSON-parsing on our side.
     *
     * <p>Package-private (not private) so unit tests can spy/override this method to inject a
     * canned {@link TriageResult} or simulate a {@link RefusalException} without standing up the
     * Anthropic SDK.
     */
    TriageResult callClaude(AnthropicClient client, String userMessage) throws RefusalException {
        StructuredMessageCreateParams<TriageResult> params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(MAX_OUTPUT_TOKENS)
                .systemOfTextBlockParams(List.of(
                        TextBlockParam.builder()
                                .text(TriagePrompts.SYSTEM_PROMPT_V2)
                                .cacheControl(CacheControlEphemeral.builder().build())
                                .build()))
                .addUserMessage(userMessage)
                .outputConfig(TriageResult.class)
                .build();

        var response = client.messages().create(params);

        // Refusal — safety classifier declined. Surface to caller for friendly handling.
        String stopReason = response.stopReason() == null ? "" : response.stopReason().toString();
        if (stopReason.contains("refusal")) {
            // stop_details may carry a category; fall back to a generic label if not.
            throw new RefusalException("policy_refusal");
        }

        var firstTyped = response.content().stream()
                .flatMap(cb -> cb.text().stream())
                .findFirst()
                .orElseThrow(() -> new TriageFailedException("Claude response had no text block", null));

        TriageResult parsed = firstTyped.text();
        // promptVersion + model are populated by us, not the model.
        return new TriageResult(
                parsed.classification(),
                parsed.confidence(),
                parsed.explanation(),
                parsed.draftMessage(),
                parsed.signals() == null ? List.of() : parsed.signals(),
                TriagePrompts.PROMPT_VERSION,
                MODEL);
    }

    /** Synthetic NEEDS_REVIEW fallback when the safety classifier refuses. */
    private TriageResult refusalFallback(String category) {
        return new TriageResult(
                TriageClassification.NEEDS_REVIEW,
                BigDecimal.ZERO,
                "This booking couldn't be classified automatically (" + category
                        + "). Please review manually.",
                "",
                List.of(),
                TriagePrompts.PROMPT_VERSION,
                MODEL);
    }

    /** Persist a triage row (or refusal row). Idempotent against unique (booking, prompt_version). */
    private SuspiciousTriage persist(String bookingId, TriageResult result,
                                     String refusalCategory, String langsmithRunId) {
        SuspiciousTriage row = SuspiciousTriage.builder()
                .squareBookingId(bookingId)
                .promptVersion(TriagePrompts.PROMPT_VERSION)
                .classification(result.classification())
                .confidence(result.confidence())
                .explanation(result.explanation())
                .draftMessage(result.draftMessage())
                .signals(result.signals())
                .model(MODEL)
                .langsmithRunId(langsmithRunId)
                .refusalCategory(refusalCategory)
                .build();
        return triages.save(row);
    }

    /** Convert a persisted row back into the carry-around shape. */
    private TriageResult toResult(SuspiciousTriage t) {
        return TriageResult.fromEntity(t);
    }

    /**
     * Keywords that suggest a free fix / redo / correction within the salon's warranty window.
     * Matched case-insensitively as substrings in the service name and notes. Intentionally tight
     * — broader words like "follow-up" have too many other meanings.
     */
    private static final String[] FIX_KEYWORDS = {
            "fix", "redo", "rework", "correction", "touch-up", "touchup"
    };

    /** Compute the named signal list from the raw candidate. The LLM cites these by name. */
    private List<String> computeSignals(SquareMonthAggregator.SuspiciousCandidate c, String tz,
                                        String serviceName) {
        List<String> signals = new ArrayList<>();
        // Three signals are always true for any suspicious candidate (they're how detection works).
        signals.add("past_appointment_no_order");
        signals.add("no_cash_note");
        signals.add("not_owner_customer");

        if (c.sellerNote() != null && !c.sellerNote().isBlank()) signals.add("has_seller_note");
        if (c.customerNote() != null && !c.customerNote().isBlank()) signals.add("has_customer_note");
        if (c.gross() == null) signals.add("gross_unknown");

        ZonedDateTime local = c.startAt().atZone(safeZone(tz));
        DayOfWeek dow = local.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) signals.add("weekend_appointment");
        if (local.getHour() >= 19) signals.add("late_evening_appointment");

        if (containsAnyIgnoreCase(serviceName, FIX_KEYWORDS)
                || containsAnyIgnoreCase(c.sellerNote(), FIX_KEYWORDS)
                || containsAnyIgnoreCase(c.customerNote(), FIX_KEYWORDS)) {
            signals.add("possible_fix_or_redo");
        }

        return signals;
    }

    /** Case-insensitive substring match against any of the given keywords. Null-safe. */
    private static boolean containsAnyIgnoreCase(String haystack, String[] keywords) {
        if (haystack == null || haystack.isBlank()) return false;
        String lower = haystack.toLowerCase(Locale.US);
        for (String kw : keywords) {
            if (lower.contains(kw)) return true;
        }
        return false;
    }

    /** Format the booking context as the model's user message — matches the few-shot example shape. */
    private String buildUserMessage(SquareMonthAggregator.SuspiciousCandidate c,
                                    String tz, String serviceName, List<String> signals) {
        ZonedDateTime local = c.startAt().atZone(safeZone(tz));
        String dayName = local.format(DAY_FMT);
        String time = local.format(TIME_FMT);

        StringBuilder sb = new StringBuilder();
        sb.append("Booking: ").append(time).append(", ").append(dayName).append('\n');
        sb.append("Service: ");
        // Show the resolved service name (when available) alongside the catalog price — gives the
        // model the "fix"/"correction" keywords it needs to detect a redo without a Square pull.
        if (serviceName != null && !serviceName.isBlank()) {
            sb.append(serviceName);
            if (c.gross() != null) sb.append(" ($").append(c.gross().toPlainString()).append(")");
            sb.append('\n');
        } else if (c.gross() == null) {
            sb.append("(catalog price unknown)").append('\n');
        } else {
            sb.append("(unknown service, catalog price $").append(c.gross().toPlainString()).append(")").append('\n');
        }
        sb.append("Customer: (id ").append(c.customerId() == null ? "(none)" : c.customerId()).append(")").append('\n');
        sb.append("Provider: ").append(c.providerName() == null ? "(unknown)" : c.providerName()).append('\n');
        sb.append("Signals fired: ").append(String.join(", ", signals)).append('\n');
        sb.append("Seller note: ").append(blankOrQuoted(c.sellerNote())).append('\n');
        sb.append("Customer note: ").append(blankOrQuoted(c.customerNote())).append('\n');
        return sb.toString();
    }

    private static String blankOrQuoted(String s) {
        return (s == null || s.isBlank()) ? "(empty)" : "\"" + s.trim() + "\"";
    }

    private static ZoneId safeZone(String tz) {
        try {
            return tz == null || tz.isBlank() ? ZoneOffset.UTC : ZoneId.of(tz);
        } catch (Exception e) {
            return ZoneOffset.UTC;
        }
    }

    /** Output map for LangSmith trace completion. */
    private static Map<String, Object> toOutputs(TriageResult r) {
        Map<String, Object> out = new HashMap<>();
        out.put("classification", r.classification().name());
        out.put("confidence", r.confidence());
        out.put("explanation", r.explanation());
        out.put("draft_message", r.draftMessage());
        out.put("signals", r.signals());
        return out;
    }

    // ---------------------------------------------------------------------- exceptions

    /** Translates to 502 in the controller layer. */
    public static class TriageFailedException extends RuntimeException {
        public TriageFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Internal — the safety classifier refused. Caught and converted to a friendly NEEDS_REVIEW.
     * Package-private (not private) so unit tests can construct one to simulate a refusal.
     */
    static class RefusalException extends Exception {
        private final String category;
        RefusalException(String category) { this.category = category; }
        String category() { return category; }
    }
}
