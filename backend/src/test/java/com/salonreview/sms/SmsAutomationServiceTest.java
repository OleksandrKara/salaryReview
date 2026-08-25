package com.salonreview.sms;

import com.salonreview.domain.SmsAutomation;
import com.salonreview.repo.RepeatCustomerWinbackSendRepository;
import com.salonreview.repo.SmsAutomationRepository;
import com.salonreview.repo.SmsMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Per-automation 30-day metrics on the {@code /owner/settings/sms} automations panel — click-
 * through and reply rate are only ever queried for automations that {@link SmsAutomationRegistry}
 * marks as tracking them (see that class's own doc for why: not every automation has a link or
 * asks for a reply).
 */
class SmsAutomationServiceTest {

    private SmsAutomationRepository repository;
    private SmsMessageRepository messageRepository;
    private RepeatCustomerWinbackSendRepository repeatCustomerWinbackSendRepository;
    private AutomationReadinessService readinessService;
    private SmsAutomationService service;
    private static final Long BUSINESS_ID = 1L;

    @BeforeEach
    void setUp() {
        repository = mock(SmsAutomationRepository.class);
        messageRepository = mock(SmsMessageRepository.class);
        repeatCustomerWinbackSendRepository = mock(RepeatCustomerWinbackSendRepository.class);
        readinessService = mock(AutomationReadinessService.class);
        when(readinessService.readiness(eq(BUSINESS_ID), anyString())).thenReturn(AutomationReadinessService.Readiness.READY);
        service = new SmsAutomationService(repository, messageRepository, repeatCustomerWinbackSendRepository, readinessService);
        when(repository.findByBusinessIdAndAutomationKey(eq(BUSINESS_ID), anyString())).thenReturn(Optional.empty());
    }

    private SmsAutomationService.AutomationSummary find(String key) {
        return service.list(BUSINESS_ID).stream().filter(a -> a.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("checkout_review_request: sent count is filtered to the primary (rating-request) templates only, not the branch reply")
    void checkoutReviewSentCountExcludesBranchReply() {
        when(messageRepository.countByBusinessIdAndAutomationKeyAndTemplateKeyInAndDirectionAndStatusAndCreatedAtAfter(
                eq(BUSINESS_ID), eq("checkout_review_request"),
                eq(List.of("checkout_rating_request", "checkout_rating_request_with_technician", "checkout_rating_request_no_technician")),
                eq("OUTBOUND"), eq("SENT"), any(Instant.class)))
                .thenReturn(10L);

        var summary = find("checkout_review_request");

        assertThat(summary.sentLast30Days()).isEqualTo(10);
    }

    @Test
    @DisplayName("checkout_review_request: tracks both clicks and replies, using the right count queries")
    void checkoutReviewTracksClicksAndReplies() {
        when(messageRepository.countByBusinessIdAndAutomationKeyAndDirectionAndStatusAndLinkTargetIsNotNullAndCreatedAtAfter(
                eq(BUSINESS_ID), eq("checkout_review_request"), eq("OUTBOUND"), eq("SENT"), any(Instant.class))).thenReturn(8L);
        when(messageRepository.countByBusinessIdAndAutomationKeyAndDirectionAndStatusAndLinkTargetIsNotNullAndClickedAtIsNotNullAndCreatedAtAfter(
                eq(BUSINESS_ID), eq("checkout_review_request"), eq("OUTBOUND"), eq("SENT"), any(Instant.class))).thenReturn(5L);
        when(messageRepository.countByBusinessIdAndAutomationKeyAndDirectionAndReplyFlowIdIsNotNullAndCreatedAtAfter(
                eq(BUSINESS_ID), eq("checkout_review_request"), eq("INBOUND"), any(Instant.class))).thenReturn(8L);

        var summary = find("checkout_review_request");

        assertThat(summary.tracksClicks()).isTrue();
        assertThat(summary.linkSentLast30Days()).isEqualTo(8);
        assertThat(summary.clickedLast30Days()).isEqualTo(5);
        assertThat(summary.tracksReplies()).isTrue();
        assertThat(summary.replyLast30Days()).isEqualTo(8);
    }

    @Test
    @DisplayName("2026-08-21 live incident: checkout_review_request's reply count is flow-scoped, "
            + "not a raw inbound count — a real business saw 857% (\"60/7\") because a negative-rating "
            + "back-and-forth conversation kept adding inbound messages under the same automationKey "
            + "well past the number of rating requests actually sent")
    void checkoutReviewReplyCountIsFlowScopedNotRawInboundCount() {
        when(messageRepository.countByBusinessIdAndAutomationKeyAndTemplateKeyInAndDirectionAndStatusAndCreatedAtAfter(
                eq(BUSINESS_ID), eq("checkout_review_request"), any(), eq("OUTBOUND"), eq("SENT"), any(Instant.class)))
                .thenReturn(7L);
        // The bug: 60 raw inbound messages (a back-and-forth conversation) vs. only 7 actual rating
        // requests sent — the fix must not use this raw count for checkout_review_request at all.
        when(messageRepository.countByBusinessIdAndAutomationKeyAndDirectionAndCreatedAtAfter(
                eq(BUSINESS_ID), eq("checkout_review_request"), eq("INBOUND"), any(Instant.class))).thenReturn(60L);
        when(messageRepository.countByBusinessIdAndAutomationKeyAndDirectionAndReplyFlowIdIsNotNullAndCreatedAtAfter(
                eq(BUSINESS_ID), eq("checkout_review_request"), eq("INBOUND"), any(Instant.class))).thenReturn(6L);

        var summary = find("checkout_review_request");

        assertThat(summary.sentLast30Days()).isEqualTo(7);
        assertThat(summary.replyLast30Days()).isEqualTo(6);
        org.mockito.Mockito.verify(messageRepository, org.mockito.Mockito.never())
                .countByBusinessIdAndAutomationKeyAndDirectionAndCreatedAtAfter(
                        eq(BUSINESS_ID), eq("checkout_review_request"), anyString(), any(Instant.class));
    }

    @Test
    @DisplayName("same_day_rebooking_discount: tracks clicks but not replies, sent count is unfiltered by template")
    void sameDayRebookingTracksClicksOnly() {
        when(messageRepository.countByBusinessIdAndAutomationKeyAndDirectionAndStatusAndCreatedAtAfter(
                eq(BUSINESS_ID), eq("same_day_rebooking_discount"), eq("OUTBOUND"), eq("SENT"), any(Instant.class))).thenReturn(20L);
        when(messageRepository.countByBusinessIdAndAutomationKeyAndDirectionAndStatusAndLinkTargetIsNotNullAndCreatedAtAfter(
                eq(BUSINESS_ID), eq("same_day_rebooking_discount"), eq("OUTBOUND"), eq("SENT"), any(Instant.class))).thenReturn(20L);
        when(messageRepository.countByBusinessIdAndAutomationKeyAndDirectionAndStatusAndLinkTargetIsNotNullAndClickedAtIsNotNullAndCreatedAtAfter(
                eq(BUSINESS_ID), eq("same_day_rebooking_discount"), eq("OUTBOUND"), eq("SENT"), any(Instant.class))).thenReturn(6L);

        var summary = find("same_day_rebooking_discount");

        assertThat(summary.sentLast30Days()).isEqualTo(20);
        assertThat(summary.tracksClicks()).isTrue();
        assertThat(summary.linkSentLast30Days()).isEqualTo(20);
        assertThat(summary.clickedLast30Days()).isEqualTo(6);
        assertThat(summary.tracksReplies()).isFalse();
        assertThat(summary.replyLast30Days()).isEqualTo(0);
    }

    @Test
    @DisplayName("four_hand_request: tracks neither clicks nor replies")
    void fourHandRequestTracksNeither() {
        var summary = find("four_hand_request");

        assertThat(summary.tracksClicks()).isFalse();
        assertThat(summary.linkSentLast30Days()).isEqualTo(0);
        assertThat(summary.clickedLast30Days()).isEqualTo(0);
        assertThat(summary.tracksReplies()).isFalse();
        assertThat(summary.replyLast30Days()).isEqualTo(0);
        assertThat(summary.tracksConversion()).isFalse();
        assertThat(summary.convertedLast30Days()).isEqualTo(0);

        org.mockito.Mockito.verify(messageRepository, org.mockito.Mockito.never())
                .countByBusinessIdAndAutomationKeyAndDirectionAndStatusAndLinkTargetIsNotNullAndCreatedAtAfter(
                        eq(BUSINESS_ID), eq("four_hand_request"), anyString(), anyString(), any(Instant.class));
        org.mockito.Mockito.verify(messageRepository, org.mockito.Mockito.never())
                .countByBusinessIdAndAutomationKeyAndDirectionAndCreatedAtAfter(eq(BUSINESS_ID), eq("four_hand_request"), anyString(), any(Instant.class));
    }

    @Test
    @DisplayName("repeat_customer_winback: tracks clicks, replies, AND conversion (did the customer actually come back)")
    void repeatCustomerWinbackTracksClicksRepliesAndConversion() {
        when(messageRepository.countByBusinessIdAndAutomationKeyAndDirectionAndStatusAndCreatedAtAfter(
                eq(BUSINESS_ID), eq("repeat_customer_winback"), eq("OUTBOUND"), eq("SENT"), any(Instant.class))).thenReturn(15L);
        when(messageRepository.countByBusinessIdAndAutomationKeyAndDirectionAndStatusAndLinkTargetIsNotNullAndCreatedAtAfter(
                eq(BUSINESS_ID), eq("repeat_customer_winback"), eq("OUTBOUND"), eq("SENT"), any(Instant.class))).thenReturn(15L);
        when(messageRepository.countByBusinessIdAndAutomationKeyAndDirectionAndStatusAndLinkTargetIsNotNullAndClickedAtIsNotNullAndCreatedAtAfter(
                eq(BUSINESS_ID), eq("repeat_customer_winback"), eq("OUTBOUND"), eq("SENT"), any(Instant.class))).thenReturn(4L);
        when(messageRepository.countByBusinessIdAndAutomationKeyAndDirectionAndCreatedAtAfter(
                eq(BUSINESS_ID), eq("repeat_customer_winback"), eq("INBOUND"), any(Instant.class))).thenReturn(3L);
        when(repeatCustomerWinbackSendRepository.countConvertedSince(eq(BUSINESS_ID), eq("SENT"), any(Instant.class))).thenReturn(6L);

        var summary = find("repeat_customer_winback");

        assertThat(summary.sentLast30Days()).isEqualTo(15);
        assertThat(summary.tracksClicks()).isTrue();
        assertThat(summary.clickedLast30Days()).isEqualTo(4);
        assertThat(summary.tracksReplies()).isTrue();
        assertThat(summary.replyLast30Days()).isEqualTo(3);
        assertThat(summary.tracksConversion()).isTrue();
        assertThat(summary.convertedLast30Days()).isEqualTo(6);
    }

    @Test
    @DisplayName("2026-08-18 live incident: isEnabled fails CLOSED (not open) for a business/key "
            + "with no row at all — found live for business 2, which had zero sms_automation rows "
            + "for any key, so every automation was effectively already on for it under the old default")
    void isEnabledFailsClosedForMissingRow() {
        when(repository.findByBusinessIdAndAutomationKey(BUSINESS_ID, "lead_follow_up")).thenReturn(Optional.empty());

        assertThat(service.isEnabled(BUSINESS_ID, "lead_follow_up")).isFalse();
    }

    @Test
    @DisplayName("isEnabled reflects an explicit enabled=true row")
    void isEnabledReflectsExplicitRow() {
        when(repository.findByBusinessIdAndAutomationKey(BUSINESS_ID, "lead_follow_up"))
                .thenReturn(Optional.of(SmsAutomation.builder().businessId(BUSINESS_ID)
                        .automationKey("lead_follow_up").enabled(true).build()));

        assertThat(service.isEnabled(BUSINESS_ID, "lead_follow_up")).isTrue();
    }

    @Test
    @DisplayName("enabled state still reflects the DB row, independent of the new metrics")
    void enabledStateUnaffected() {
        when(repository.findByBusinessIdAndAutomationKey(BUSINESS_ID, "lead_follow_up"))
                .thenReturn(Optional.of(SmsAutomation.builder().businessId(BUSINESS_ID).automationKey("lead_follow_up").enabled(true).build()));

        var summary = find("lead_follow_up");

        assertThat(summary.enabled()).isTrue();
    }

    @Test
    @DisplayName("list() surfaces readiness per automation, independent of enabled state")
    void listSurfacesReadiness() {
        when(readinessService.readiness(BUSINESS_ID, "checkout_review_request"))
                .thenReturn(AutomationReadinessService.Readiness.notReady("Set your Google review link first"));

        var summary = find("checkout_review_request");

        assertThat(summary.ready()).isFalse();
        assertThat(summary.readinessReason()).isEqualTo("Set your Google review link first");
    }

    @Test
    @DisplayName("setEnabled(true) is refused when the automation isn't ready — no row written")
    void setEnabledRefusedWhenNotReady() {
        when(readinessService.readiness(BUSINESS_ID, "touchup_reminder"))
                .thenReturn(AutomationReadinessService.Readiness.notReady("Add at least one service first"));

        assertThatThrownBy(() -> service.setEnabled(BUSINESS_ID, "touchup_reminder", true, "owner@test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Add at least one service first");
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("setEnabled(true) succeeds when ready")
    void setEnabledSucceedsWhenReady() {
        service.setEnabled(BUSINESS_ID, "touchup_reminder", true, "owner@test");

        org.mockito.Mockito.verify(repository).save(any());
    }

    @Test
    @DisplayName("setEnabled(false) is never blocked by readiness, even if not ready")
    void setEnabledFalseNeverBlocked() {
        when(readinessService.readiness(BUSINESS_ID, "touchup_reminder"))
                .thenReturn(AutomationReadinessService.Readiness.notReady("Add at least one service first"));

        service.setEnabled(BUSINESS_ID, "touchup_reminder", false, "owner@test");

        org.mockito.Mockito.verify(repository).save(any());
    }
}
