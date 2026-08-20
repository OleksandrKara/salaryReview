package com.salonreview.sms;

import com.salonreview.domain.Provider;
import com.salonreview.domain.SmsMessage;
import com.salonreview.domain.SmsReplyFlow;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.SmsMessageRepository;
import com.salonreview.repo.SmsReplyFlowRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds the {@code /owner/reviews} dashboard's data: every reply to the checkout-review-request
 * rating request, grouped by provider, with an overall and per-provider average — see V120. Read
 * path only; the actual per-message {@code provider_id}/{@code reply_flow_id}/{@code rating}
 * values are written by {@link SmsReplyFlowScheduler} (live sends), {@link
 * TwilioInboundSmsController} (live replies), and {@link CheckoutReviewProviderRatingBackfillStartup}
 * (everything from before those two existed).
 */
@Service
public class CheckoutReviewInsightsService {

    static final String AUTOMATION_KEY = "checkout_review_request";

    /** A review whose flow never resolved a provider (no matching booking, or a customer who
     * walked in with no appointment on file at all) — grouped under this synthetic id rather than
     * dropped, so the owner still sees it counted somewhere instead of it silently vanishing from
     * the total. */
    public static final long UNASSIGNED_PROVIDER_ID = -1L;

    private final SmsMessageRepository messages;
    private final SmsReplyFlowRepository flows;
    private final ProviderRepository providers;

    public CheckoutReviewInsightsService(SmsMessageRepository messages, SmsReplyFlowRepository flows,
                                          ProviderRepository providers) {
        this.messages = messages;
        this.flows = flows;
        this.providers = providers;
    }

    public record ReviewView(Long messageId, Long providerId, String providerName, Integer rating, String body,
                              String phoneNumber, String customerName, java.time.Instant createdAt) {}

    public record ProviderSummary(Long providerId, String providerName, Double averageRating, long ratedCount,
                                   long unratedCount) {
        long totalCount() {
            return ratedCount + unratedCount;
        }
    }

    public record Overview(Double averageRating, long ratedCount, long totalCount,
                            List<ProviderSummary> byProvider, List<ReviewView> reviews) {}

    public Overview overview(Long businessId) {
        List<SmsMessage> replies = messages.findByBusinessIdAndAutomationKeyAndDirectionOrderByCreatedAtDesc(
                businessId, AUTOMATION_KEY, "INBOUND");

        Set<Long> flowIds = replies.stream().map(SmsMessage::getReplyFlowId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, SmsReplyFlow> flowById = flows.findAllById(flowIds).stream()
                .collect(Collectors.toMap(SmsReplyFlow::getId, f -> f));
        Set<Long> providerIds = flowById.values().stream().map(SmsReplyFlow::getProviderId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> providerNameById = providers.findAllById(providerIds).stream()
                .collect(Collectors.toMap(Provider::getId, Provider::getDisplayName));

        List<ReviewView> reviews = new ArrayList<>();
        Map<Long, List<SmsMessage>> byProviderId = new HashMap<>();
        for (SmsMessage m : replies) {
            SmsReplyFlow flow = m.getReplyFlowId() == null ? null : flowById.get(m.getReplyFlowId());
            Long providerId = flow == null ? null : flow.getProviderId();
            String providerName = providerId == null ? null : providerNameById.get(providerId);
            reviews.add(new ReviewView(m.getId(), providerId, providerName, m.getRating(), m.getBody(),
                    m.getPhoneNumber(), flow == null ? null : flow.getCustomerName(), m.getCreatedAt()));
            byProviderId.computeIfAbsent(providerId == null ? UNASSIGNED_PROVIDER_ID : providerId,
                    k -> new ArrayList<>()).add(m);
        }

        List<ProviderSummary> byProvider = byProviderId.entrySet().stream()
                .map(e -> summarize(e.getKey(), e.getKey() == UNASSIGNED_PROVIDER_ID ? "No technician on file"
                        : providerNameById.getOrDefault(e.getKey(), "Unknown"), e.getValue()))
                .sorted(Comparator.comparing(ProviderSummary::providerName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        long ratedCount = replies.stream().filter(m -> m.getRating() != null).count();
        Double averageRating = ratedCount == 0 ? null
                : replies.stream().filter(m -> m.getRating() != null).mapToInt(SmsMessage::getRating).average().orElseThrow();

        return new Overview(averageRating, ratedCount, replies.size(), byProvider, reviews);
    }

    private static ProviderSummary summarize(Long providerId, String providerName, List<SmsMessage> forProvider) {
        long rated = forProvider.stream().filter(m -> m.getRating() != null).count();
        long unrated = forProvider.size() - rated;
        Double avg = rated == 0 ? null
                : forProvider.stream().filter(m -> m.getRating() != null).mapToInt(SmsMessage::getRating).average().orElseThrow();
        return new ProviderSummary(providerId, providerName, avg, rated, unrated);
    }
}
