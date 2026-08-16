package com.salonreview.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.salonreview.config.AiSmsDraftProperties;
import com.salonreview.domain.Language;
import com.salonreview.domain.RagAgentConfig;
import com.salonreview.domain.SmsMessage;
import com.salonreview.marketing.MarketingContactsService;
import com.salonreview.rag.RagConfigService;
import com.salonreview.rag.RagRetrievalService;
import com.salonreview.repo.ChunkMatch;
import com.salonreview.sms.SmsMessageLogService;
import com.salonreview.util.Names;
import com.salonreview.web.dto.MarketingContactDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Drafts an SMS reply for the manager conversation view's "Generate" button ({@code /admin/messages}
 * — see {@code SmsActivityController#draftReply}). Assembles the same kind of context a manager
 * would look at before replying by hand — the thread so far, the customer's real visit history, and
 * (when there's an actual customer question/concern to ground against) a RAG lookup against salon
 * policy — then asks Claude to propose one message in the salon's existing SMS voice (see
 * {@link SmsDraftPrompts}). Returns a draft only; the manager still reviews/edits/sends it via the
 * existing {@code /reply} endpoint, so this never touches send/consent gating.
 */
@Service
public class SmsDraftService {

    private static final Logger log = LoggerFactory.getLogger(SmsDraftService.class);

    /** Sonnet, not Haiku — same reasoning-quality-over-cost call as {@link FunnelAnalysisService},
     * for the same reason: a low-frequency, manager-triggered "consultant" task, not a high-volume
     * background job. */
    static final String MODEL = "claude-sonnet-5";

    private static final long MAX_OUTPUT_TOKENS = 512L;

    /** How much of a conversation's history to show Claude — enough to see a real back-and-forth
     * (including an earlier concern that's since gone quiet) without spending tokens on an entire
     * multi-year thread. */
    private static final int MAX_THREAD_MESSAGES = 30;

    private static final int MAX_APPOINTMENTS = 8;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);

    private final ObjectProvider<AnthropicClient> anthropicClientProvider;
    private final AiSmsDraftProperties props;
    private final SmsMessageLogService smsMessageLogService;
    private final MarketingContactsService contactsService;
    private final ObjectProvider<RagRetrievalService> ragRetrievalProvider;
    private final ObjectProvider<RagConfigService> ragConfigProvider;

    public SmsDraftService(ObjectProvider<AnthropicClient> anthropicClientProvider,
                            AiSmsDraftProperties props,
                            SmsMessageLogService smsMessageLogService,
                            MarketingContactsService contactsService,
                            ObjectProvider<RagRetrievalService> ragRetrievalProvider,
                            ObjectProvider<RagConfigService> ragConfigProvider) {
        this.anthropicClientProvider = anthropicClientProvider;
        this.props = props;
        this.smsMessageLogService = smsMessageLogService;
        this.contactsService = contactsService;
        this.ragRetrievalProvider = ragRetrievalProvider;
        this.ragConfigProvider = ragConfigProvider;
    }

    public record DraftResult(String body, String promptVersion, String model) {}

    /** Empty when the feature is off or Claude isn't configured (→ 404 in the controller, mirroring
     * every other AI feature's ships-dark convention). Never returns empty for "nothing to draft
     * from" — an empty thread still gets a reasonable generic rebooking nudge. */
    public Optional<DraftResult> draft(String phoneNumber, Language lang, Long businessId) {
        if (!props.isEnabled()) return Optional.empty();
        AnthropicClient client = anthropicClientProvider.getIfAvailable();
        if (client == null) return Optional.empty();

        List<SmsMessage> thread = smsMessageLogService.thread(phoneNumber);
        MarketingContactDto.Contact contact = contactsService.contactByPhone(phoneNumber).orElse(null);
        List<ChunkMatch> ragMatches = retrieveGrounding(thread, businessId);

        String userMessage = buildUserMessage(contact, thread, ragMatches);
        try {
            return Optional.of(callClaude(client, userMessage, lang));
        } catch (RefusalException re) {
            log.warn("SMS draft refused for phone={}: {}", phoneNumber, re.category());
            return Optional.of(refusalFallback(lang));
        } catch (Exception e) {
            log.error("Claude SMS draft failed for phone={}: {}", phoneNumber, e.toString());
            throw new DraftFailedException("LLM call failed", e);
        }
    }

    /** Only bothers with a RAG lookup when there's something concrete to ground against — the most
     * recent inbound message, used verbatim as the retrieval query. A drafted rebooking nudge with
     * no open question doesn't need salon-policy grounding, and gracefully no-ops (empty list) when
     * {@code rag.enabled} is off, since {@link RagRetrievalService}/{@link RagConfigService} are
     * themselves conditional beans. */
    private List<ChunkMatch> retrieveGrounding(List<SmsMessage> thread, Long businessId) {
        RagRetrievalService retrieval = ragRetrievalProvider.getIfAvailable();
        RagConfigService ragConfigService = ragConfigProvider.getIfAvailable();
        if (retrieval == null || ragConfigService == null) return List.of();

        String lastInbound = thread.stream()
                .filter(m -> "INBOUND".equals(m.getDirection()))
                .reduce((first, second) -> second)
                .map(SmsMessage::getBody)
                .orElse(null);
        if (lastInbound == null || lastInbound.isBlank()) return List.of();

        try {
            RagAgentConfig cfg = ragConfigService.getActive(businessId);
            return retrieval.retrieve(lastInbound, cfg, businessId);
        } catch (Exception e) {
            log.warn("RAG grounding lookup failed for SMS draft, continuing without it: {}", e.toString());
            return List.of();
        }
    }

    private String buildUserMessage(MarketingContactDto.Contact contact, List<SmsMessage> thread, List<ChunkMatch> ragMatches) {
        StringBuilder sb = new StringBuilder();

        sb.append("Customer:\n");
        String firstName = contact != null ? Names.capitalizeFirst(contact.givenName()) : null;
        sb.append("- First name: ").append(firstName != null ? firstName : "(unknown — greet without a name)").append('\n');
        if (contact != null) {
            sb.append("- VIP customer: ").append(contact.vip() ? "yes" : "no").append('\n');
            if (contact.visitCount() != null) {
                sb.append("- Distinct visits on record: ").append(contact.visitCount()).append('\n');
            }
        }

        sb.append("\nAppointment history (most recent/upcoming first):\n");
        List<MarketingContactDto.Appointment> appointments = contact != null ? contact.appointments() : List.of();
        if (appointments.isEmpty()) {
            sb.append("(none on record)\n");
        } else {
            for (MarketingContactDto.Appointment appt : appointments.stream().limit(MAX_APPOINTMENTS).toList()) {
                sb.append("- ").append(formatDate(appt)).append(": ").append(appt.serviceName());
                if (appt.artistName() != null) sb.append(" with ").append(appt.artistName());
                if (appt.status() != null) sb.append(" [").append(appt.status()).append(']');
                sb.append('\n');
            }
        }

        sb.append("\nConversation so far (oldest first):\n");
        List<SmsMessage> recent = thread.size() > MAX_THREAD_MESSAGES
                ? thread.subList(thread.size() - MAX_THREAD_MESSAGES, thread.size())
                : thread;
        if (recent.isEmpty()) {
            sb.append("(no prior messages — this would be the first text to this customer)\n");
        } else {
            for (SmsMessage m : recent) {
                sb.append("- ").append("INBOUND".equals(m.getDirection()) ? "Customer" : "Salon")
                        .append(": ").append(m.getBody()).append('\n');
            }
        }

        if (!ragMatches.isEmpty()) {
            sb.append("\nRelevant salon policy/knowledge (for grounding only — don't quote verbatim, don't cite):\n");
            for (ChunkMatch match : ragMatches) {
                sb.append("- ").append(match.getChunkText()).append('\n');
            }
        }

        sb.append("\nDraft the salon's next SMS reply now.");
        return sb.toString();
    }

    private String formatDate(MarketingContactDto.Appointment appt) {
        if (appt.startAt() == null) return "(date unknown)";
        return DATE_FMT.format(appt.startAt().atZone(ZoneId.of("America/Los_Angeles")));
    }

    /** Package-private so tests can override — same pattern as {@link FunnelAnalysisService#callClaude}. */
    DraftResult callClaude(AnthropicClient client, String userMessage, Language lang) throws RefusalException {
        List<TextBlockParam> system = new ArrayList<>();
        system.add(TextBlockParam.builder()
                .text(SmsDraftPrompts.SYSTEM_PROMPT_V2)
                .cacheControl(CacheControlEphemeral.builder().build())
                .build());
        String directive = SmsDraftPrompts.languageDirective(lang);
        if (directive != null) {
            system.add(TextBlockParam.builder().text(directive).build());
        }

        MessageCreateParams params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(MAX_OUTPUT_TOKENS)
                .systemOfTextBlockParams(system)
                .addUserMessage(userMessage)
                .build();

        Message response = client.messages().create(params);

        String stopReason = response.stopReason() == null ? "" : response.stopReason().toString();
        if (stopReason.contains("refusal")) {
            throw new RefusalException("policy_refusal");
        }

        String text = response.content().stream()
                .flatMap(cb -> cb.text().stream())
                .findFirst()
                .map(tb -> tb.text().trim())
                .orElseThrow(() -> new DraftFailedException("Claude response had no text block", null));

        return new DraftResult(text, SmsDraftPrompts.PROMPT_VERSION, MODEL);
    }

    private DraftResult refusalFallback(Language lang) {
        String body = lang == Language.RU
                ? "Не удалось автоматически составить черновик сообщения. Пожалуйста, напишите ответ вручную."
                : "Couldn't auto-draft a reply this time — please write one manually.";
        return new DraftResult(body, SmsDraftPrompts.PROMPT_VERSION, MODEL);
    }

    /** Translates to 502 in the controller layer. */
    public static class DraftFailedException extends RuntimeException {
        public DraftFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Internal — the safety classifier refused. Caught and converted to a friendly fallback. */
    static class RefusalException extends Exception {
        private final String category;
        RefusalException(String category) { this.category = category; }
        String category() { return category; }
    }
}
