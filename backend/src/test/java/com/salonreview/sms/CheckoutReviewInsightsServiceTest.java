package com.salonreview.sms;

import com.salonreview.domain.Provider;
import com.salonreview.domain.SmsMessage;
import com.salonreview.domain.SmsReplyFlow;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.SmsMessageRepository;
import com.salonreview.repo.SmsReplyFlowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The {@code /owner/reviews} dashboard's data assembly — see V120. */
class CheckoutReviewInsightsServiceTest {

    private static final Long BUSINESS_ID = 1L;

    private SmsMessageRepository messages;
    private SmsReplyFlowRepository flows;
    private ProviderRepository providers;
    private CheckoutReviewInsightsService service;

    @BeforeEach
    void setUp() {
        messages = mock(SmsMessageRepository.class);
        flows = mock(SmsReplyFlowRepository.class);
        providers = mock(ProviderRepository.class);
        service = new CheckoutReviewInsightsService(messages, flows, providers);
    }

    private static SmsMessage reply(Long id, Long replyFlowId, Integer rating, String body) {
        return SmsMessage.builder().id(id).businessId(BUSINESS_ID).direction("INBOUND")
                .automationKey("checkout_review_request").phoneNumber("+15551234567").body(body).status("RECEIVED")
                .replyFlowId(replyFlowId).rating(rating).createdAt(Instant.now()).build();
    }

    @Test
    @DisplayName("no reviews at all → empty overview, null average")
    void noReviewsIsEmpty() {
        when(messages.findByBusinessIdAndAutomationKeyAndDirectionOrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(List.of());
        when(flows.findAllById(any())).thenReturn(List.of());
        when(providers.findAllById(any())).thenReturn(List.of());

        var overview = service.overview(BUSINESS_ID);

        assertThat(overview.averageRating()).isNull();
        assertThat(overview.ratedCount()).isZero();
        assertThat(overview.totalCount()).isZero();
        assertThat(overview.byProvider()).isEmpty();
        assertThat(overview.reviews()).isEmpty();
    }

    @Test
    @DisplayName("reviews for two providers → correct per-provider average and overall average")
    void ratingsGroupedByProvider() {
        SmsReplyFlow flowSusan1 = SmsReplyFlow.builder().id(101L).providerId(7L).customerName("Jane").build();
        SmsReplyFlow flowSusan2 = SmsReplyFlow.builder().id(102L).providerId(7L).customerName("Bob").build();
        SmsReplyFlow flowMike = SmsReplyFlow.builder().id(103L).providerId(8L).customerName("Amy").build();

        List<SmsMessage> replies = List.of(
                reply(1L, 101L, 5, "5 stars!"),
                reply(2L, 102L, 3, "3, could be better"),
                reply(3L, 103L, 1, "1, terrible"));

        when(messages.findByBusinessIdAndAutomationKeyAndDirectionOrderByCreatedAtDesc(BUSINESS_ID, "checkout_review_request", "INBOUND"))
                .thenReturn(replies);
        when(flows.findAllById(any())).thenReturn(List.of(flowSusan1, flowSusan2, flowMike));
        when(providers.findAllById(any())).thenReturn(List.of(
                Provider.builder().id(7L).displayName("Susan").build(),
                Provider.builder().id(8L).displayName("Mike").build()));

        var overview = service.overview(BUSINESS_ID);

        assertThat(overview.totalCount()).isEqualTo(3);
        assertThat(overview.ratedCount()).isEqualTo(3);
        assertThat(overview.averageRating()).isEqualTo(3.0); // (5+3+1)/3

        var susan = overview.byProvider().stream().filter(p -> p.providerId().equals(7L)).findFirst().orElseThrow();
        assertThat(susan.averageRating()).isEqualTo(4.0); // (5+3)/2
        assertThat(susan.ratedCount()).isEqualTo(2);

        var mike = overview.byProvider().stream().filter(p -> p.providerId().equals(8L)).findFirst().orElseThrow();
        assertThat(mike.averageRating()).isEqualTo(1.0);
        assertThat(mike.ratedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a reply with no linked flow, or a flow with no resolved provider, is bucketed under the unassigned pseudo-provider — not dropped")
    void unresolvedProviderIsBucketedNotDropped() {
        SmsReplyFlow flowNoProvider = SmsReplyFlow.builder().id(201L).providerId(null).customerName("Alex").build();
        List<SmsMessage> replies = List.of(
                reply(1L, 201L, 4, "4, pretty good"),
                reply(2L, null, null, "not sure how to rate this"));

        when(messages.findByBusinessIdAndAutomationKeyAndDirectionOrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(replies);
        when(flows.findAllById(any())).thenReturn(List.of(flowNoProvider));
        when(providers.findAllById(any())).thenReturn(List.of());

        var overview = service.overview(BUSINESS_ID);

        assertThat(overview.totalCount()).isEqualTo(2);
        assertThat(overview.ratedCount()).isEqualTo(1);
        assertThat(overview.byProvider()).hasSize(1);
        var unassigned = overview.byProvider().get(0);
        assertThat(unassigned.providerId()).isEqualTo(CheckoutReviewInsightsService.UNASSIGNED_PROVIDER_ID);
        assertThat(unassigned.providerName()).isEqualTo("No technician on file");
        assertThat(unassigned.totalCount()).isEqualTo(2);
        assertThat(unassigned.ratedCount()).isEqualTo(1);
        assertThat(unassigned.unratedCount()).isEqualTo(1);

        assertThat(overview.reviews()).extracting(CheckoutReviewInsightsService.ReviewView::body)
                .containsExactlyInAnyOrder("4, pretty good", "not sure how to rate this");
        var unratedReview = overview.reviews().stream().filter(r -> r.rating() == null).findFirst().orElseThrow();
        assertThat(unratedReview.providerName()).isNull();
    }
}
