package com.salonreview.sms;

import com.salonreview.domain.SmsMessage;
import com.salonreview.domain.SmsReplyFlow;
import com.salonreview.repo.SmsMessageRepository;
import com.salonreview.repo.SmsReplyFlowRepository;
import com.salonreview.repo.SquareConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-08-21 live incident: {@code checkout_review_request}'s reply-rate stat showed 857%
 * ("60/7") because every message in a negative-rating back-and-forth conversation matched and
 * linked to the same flow, not just the customer's actual first reply.
 */
class CheckoutReviewProviderRatingBackfillStartupTest {

    private static final Long BUSINESS_ID = 1L;
    private static final String PHONE = "+15551234567";

    private SmsReplyFlowRepository flows;
    private SmsMessageRepository messages;
    private TechnicianNameResolver technicianNameResolver;
    private CheckoutReviewProviderRatingBackfillStartup backfill;

    @BeforeEach
    void setUp() {
        flows = mock(SmsReplyFlowRepository.class);
        messages = mock(SmsMessageRepository.class);
        technicianNameResolver = mock(TechnicianNameResolver.class);
        backfill = new CheckoutReviewProviderRatingBackfillStartup(
                flows, messages, technicianNameResolver, mock(SquareConnectionRepository.class));
    }

    private static SmsMessage inbound(long id, Instant createdAt, String body) {
        return SmsMessage.builder().id(id).businessId(BUSINESS_ID).direction("INBOUND")
                .automationKey("checkout_review_request").phoneNumber(PHONE).body(body).status("RECEIVED")
                .createdAt(createdAt).build();
    }

    @Test
    @DisplayName("two unlinked messages both matching the same flow → only the older (the real reply) gets linked, the follow-up stays unlinked")
    void onlyOldestMessagePerFlowGetsLinked() {
        Instant reply = Instant.parse("2026-08-01T10:00:00Z");
        Instant followUp = Instant.parse("2026-08-01T10:05:00Z");
        SmsMessage first = inbound(1L, reply, "2, not great");
        SmsMessage second = inbound(2L, followUp, "the color was uneven too");
        when(messages.findDistinctReplyFlowIdsByBusinessIdAndAutomationKey(BUSINESS_ID, "checkout_review_request"))
                .thenReturn(List.of());
        when(messages.findByBusinessIdAndAutomationKeyAndDirectionAndReplyFlowIdIsNullOrderByCreatedAtAsc(
                BUSINESS_ID, "checkout_review_request", "INBOUND")).thenReturn(List.of(first, second));
        SmsReplyFlow flow = SmsReplyFlow.builder().id(99L).build();
        when(flows.findFirstByBusinessIdAndPhoneNumberAndAutomationKeyAndCreatedAtBeforeOrderByCreatedAtDesc(
                eq(BUSINESS_ID), eq(PHONE), eq("checkout_review_request"), any(Instant.class)))
                .thenReturn(Optional.of(flow));

        int linked = backfill.backfillMessageLinks(BUSINESS_ID);

        assertThat(linked).isEqualTo(1);
        assertThat(first.getReplyFlowId()).isEqualTo(99L);
        assertThat(first.getRating()).isEqualTo(2);
        assertThat(second.getReplyFlowId()).isNull();
        verify(messages).save(first);
        verify(messages, never()).save(second);
    }

    @Test
    @DisplayName("a flow that already has a linked reply (live linking, or a prior backfill run) is never re-claimed by a later backfill pass")
    void alreadyClaimedFlowIsSkipped() {
        Instant followUp = Instant.parse("2026-08-01T10:05:00Z");
        SmsMessage second = inbound(2L, followUp, "the color was uneven too");
        when(messages.findDistinctReplyFlowIdsByBusinessIdAndAutomationKey(BUSINESS_ID, "checkout_review_request"))
                .thenReturn(List.of(99L)); // already claimed by an earlier live-linked reply
        when(messages.findByBusinessIdAndAutomationKeyAndDirectionAndReplyFlowIdIsNullOrderByCreatedAtAsc(
                BUSINESS_ID, "checkout_review_request", "INBOUND")).thenReturn(List.of(second));
        SmsReplyFlow flow = SmsReplyFlow.builder().id(99L).build();
        when(flows.findFirstByBusinessIdAndPhoneNumberAndAutomationKeyAndCreatedAtBeforeOrderByCreatedAtDesc(
                eq(BUSINESS_ID), eq(PHONE), eq("checkout_review_request"), any(Instant.class)))
                .thenReturn(Optional.of(flow));

        int linked = backfill.backfillMessageLinks(BUSINESS_ID);

        assertThat(linked).isEqualTo(0);
        assertThat(second.getReplyFlowId()).isNull();
        verify(messages, never()).save(any());
    }
}
